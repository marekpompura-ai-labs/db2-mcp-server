# ADR 0031: Draining in-flight tool calls before stdin-EOF shutdown

## Status
Accepted — implemented and verified in this environment. Reproduced the
original bug directly against the real packaged jar, then reproduced it
again with the fix reverted (via `git checkout` of the two changed files)
to confirm the new regression script actually catches it, not just that
it happens to pass. `mvn verify -DskipITs` green (206 tests, up from 198).

## Date
Proposed 2026-08-17, Accepted 2026-08-17.

## Context
A live-DB2 verification session (closing out ADR 0022, see that ADR and
`docs/demo/guided-scenarios.md`) surfaced a new finding, out of scope for
that task but flagged for follow-up: a client that sends a tool call and
closes stdin before the response arrives can lose that response entirely
- not delayed, silently dropped. Investigated and confirmed real in this
environment (no live DB2 needed to reproduce it - an unreachable
`DB2_HOST`, matching `scripts/smoke-test.sh`'s own `.invalid`-TLD
technique, reliably creates the same timing window).

## Root cause
Two independent problems, both real, found by tracing the actual
mechanism rather than guessing at one:

1. **The MCP SDK's own design conflates "stop reading" with "stop
   writing."** `StdioServerTransportProvider`'s inbound reader and
   outbound writer run on two independent reactor schedulers, coordinated
   only by a shared `isClosing` flag. On EOF, the inbound thread
   unconditionally sets `isClosing = true`; the outbound thread checks
   that same flag before writing each queued response - one still in
   flight at that instant is silently dropped (`sink.complete()` instead
   of being written), no matter how long anything downstream waits
   afterward. Confirmed by reading the SDK's own source (fetched via
   `mvn dependency:sources`, since no local decompiler was available) -
   this wasn't inferred from behavior alone.
2. **This project's own `StdioLifecycleConfig` (ADR 0016) made it much
   worse.** `EofTriggeredShutdownInputStream`'s callback fired
   `SpringApplication.exit()` -> `System.exit()` on a separate thread,
   completely unsynchronized with the SDK's outbound flush or with tool
   execution (which runs on a `boundedElastic` thread, a third,
   independent thread). In the first reproduction, this killed the JVM
   roughly 10-20ms after detecting EOF - not remotely long enough for a
   real tool call to complete, let alone for its response to be written.

Real MCP clients don't close stdin with a request outstanding, which is
why this went unnoticed until a live-DB2 session's timing happened to
trigger it.

## Decision drivers
- A tool call's response silently vanishing is a correctness bug, not a
  cosmetic one - the client has no way to distinguish "dropped" from "the
  server crashed" or "still processing."
- The fix has to address problem 1 above, not just problem 2. Delaying
  `System.exit()` (fixing problem 2 alone) was tried first and verified,
  empirically, to be insufficient: a version of this fix that only
  delayed `System.exit()` let the tool call finish and its response get
  enqueued, confirmed by the exported span and recorded metrics - and the
  response was *still* dropped, because the SDK's own `isClosing` flag
  had already been set the moment EOF was first observed, independent of
  how long the JVM stayed alive afterward.
- No reasonable fix can patch the third-party SDK's internals directly
  without forking it. The one piece of the pipeline this project already
  owns and controls is the `InputStream` wrapper
  (`EofTriggeredShutdownInputStream`) passed into the SDK's constructor -
  everything downstream of that is out of reach.
- Reusing `spring.lifecycle.timeout-per-shutdown-phase` (ADR 0016) for
  the grace-period bound, rather than adding a second, separately
  configured timeout for the same underlying question ("how long is this
  process allowed to take to shut down").

## Considered options
1. **Only delay `System.exit()` until in-flight calls drain.** Tried
   first, verified insufficient by direct reproduction - see Decision
   drivers. Kept as one part of the final fix (it's still necessary, just
   not sufficient on its own), not discarded.
2. **Fork/replace the SDK's `StdioServerTransportProvider`** with a
   version that separates "stop reading" from "stop writing," only
   blocking outbound writes once genuinely safe to do so. Rejected as
   disproportionate - a substantial, ongoing maintenance burden (tracking
   a forked copy of ~170 lines of third-party reactive code) for a
   problem solvable without touching the SDK at all.
3. **Block the EOF callback itself, before returning -1 to the SDK.**
   Chosen. `EofTriggeredShutdownInputStream` already fires its callback
   *synchronously*, from inside the `read()` call that first observes
   EOF, before that call returns -1 - a detail already true of the
   existing ADR 0016 code, not something added for this fix. Making that
   callback block (waiting out `InFlightToolCallTracker`) means the SDK's
   reader thread simply doesn't learn EOF happened - and doesn't set its
   own `isClosing` - until it's safe to know. The SDK's own problem 1
   never gets a chance to trigger, because its precondition (the SDK
   observing EOF while something is in flight) is prevented at the
   source.
4. **How to know something is "in flight."** `InFlightToolCallTracker`
   (a plain `AtomicInteger`) wraps every `ToolCallback.call(...)` via a
   single decorator (`InFlightTrackingToolCallback`) applied once, in
   `McpConfig`, to all ten tools - not ten individual instrumentation
   points. `ToolCallback.call` is Spring AI's own precise synchronous
   boundary for "a tool call started" / "finished," so this needed no
   changes to any of the tool classes themselves.

## Decision
`EofTriggeredShutdownInputStream`'s callback (`StdioLifecycleConfig`) now
does three things in order, before the SDK is allowed to observe EOF:
1. **`DISPATCH_SETTLE_DELAY` (300ms), unconditional.** Found necessary by
   direct reproduction of a second race: the SDK enqueues an already-read
   request into a reactive sink and returns immediately; dispatch to the
   actual tool callback (where `InFlightToolCallTracker` increments)
   happens slightly later, via a scheduler handoff to `boundedElastic`.
   Checking the tracker before that handoff completes sees zero in-flight
   calls even though a request is genuinely queued - exactly the case
   this whole mechanism exists to protect. Without this delay, the fix
   still failed the same reproduction it was meant to close.
2. **`InFlightToolCallTracker.awaitDrain(shutdownGracePeriod)`**, capped
   at `spring.lifecycle.timeout-per-shutdown-phase` (10s). Logs a warning
   and proceeds anyway on timeout, rather than hanging indefinitely - a
   tool call stuck past this bound is already a different problem this
   fix isn't meant to solve.
3. Only then does the callback return, letting `read()` hand -1 back to
   the SDK - which now sets its own `isClosing` with nothing in flight,
   safely.

The background shutdown thread (`SpringApplication.exit()` ->
`System.exit()`) still runs off this thread, per ADR 0016's own original
reasoning (context-close callbacks must not run on the thread inside the
SDK's `read()` call). It adds one more small, fixed
`OUTBOUND_FLUSH_SETTLE_DELAY` (250ms) before exiting, for the SDK's own
outbound scheduler to actually write and flush a response that was
enqueued right at the drain boundary - the tracker's count drops when a
tool method *returns*, not when its response has been written to stdout.

## Consequences
- **A real, previously-silent bug is now closed and covered.** Verified
  three separate times against the real packaged jar: the original
  (broken) code, an intermediate fix (delay-only, still broken, kept as
  documented history in this ADR rather than discarded), and the final
  fix (passing, reliably, across three repeated runs).
- **A dedicated regression script**,
  `scripts/eof-shutdown-race-test.sh`, mirrors `smoke-test.sh`'s
  technique (unreachable `DB2_HOST`, real packaged jar, FIFO-fed stdin)
  but does the opposite of what `smoke-test.sh` deliberately does: closes
  stdin *immediately* after sending a request, specifically to exercise
  the race path. Confirmed to fail against the pre-fix code (via a
  temporary `git checkout` of the two changed files, not by trusting the
  new fix worked from a single passing run) and pass against the fix.
- **Two new small, focused classes** (`InFlightToolCallTracker`,
  `InFlightTrackingToolCallback`), each unit-tested independently -
  8 new tests, including a decrement-on-exception case (the delegate
  throwing must not leak the counter) and a genuine drain-blocks-until-
  signaled test using a real background thread, not just a synchronous
  call. Full suite: 206 tests (up from 198).
- **This is a mitigation with a documented residual limit, not a
  mathematical guarantee.** The `DISPATCH_SETTLE_DELAY`/
  `OUTBOUND_FLUSH_SETTLE_DELAY` values are fixed, chosen to comfortably
  exceed realistic reactor-scheduling latency (single-digit
  milliseconds) - not proven correct for every possible system load. A
  sufficiently starved scheduler could still theoretically lose a
  response in the same way. This is stated plainly rather than claimed
  as airtight; the practical risk is now "vanishingly rare under extreme
  load" rather than "the common case whenever a client doesn't wait for
  its response."
- **Ordinary shutdown latency is essentially unaffected.** The 300ms/
  250ms delays only run on the stdin-EOF shutdown path, which real MCP
  clients only trigger once, on disconnect - not on every tool call.
  `scripts/smoke-test.sh` (which exercises the *other* shutdown path,
  SIGTERM, deliberately untouched by this change) still passes unchanged.

## Verification / enforcement
`mvn verify -DskipITs` (206 tests). `scripts/eof-shutdown-race-test.sh`
against the real packaged jar - confirmed to fail against the pre-fix
code and pass against the fix, not merely observed passing once.
`scripts/smoke-test.sh` re-run unchanged and still green, confirming the
unrelated SIGTERM shutdown path wasn't affected.

A real gap in this ADR's own first version, caught immediately after: the
new script existed but wasn't wired into `.github/workflows/ci.yml`,
meaning CI would have kept passing even if this exact bug regressed - the
same class of mistake CR-006/CR-007 (ADR 0024) already found once in this
project (a check that exists but never actually runs). Added as a CI step
immediately after `smoke-test.sh` in the same job.

**Independently re-verified 2026-08-18** against real, reachable DB2
(the same local Docker instance used for ADR 0022's live-DB2 session),
not just the simulated-unreachable-host scenario above: a `listTables`
call against the loaded ITSM schema was still executing (mid-query,
~280ms from completing) when stdin closed, and its response - a genuine
successful result, not a simulated failure - still arrived. The fix
holds against a real backend, not just the synthetic DNS-timeout
reproduction.

That re-verification also surfaced a real, separate finding:
`scripts/eof-shutdown-race-test.sh` as originally written did not run
natively on Windows via Git Bash/MSYS - its `mkfifo`-based stdin didn't
produce a Win32 handle a native-Windows JVM's stdin reader can use,
failing immediately with `IOException: The handle is invalid`. Worse,
that failure left the JVM hanging indefinitely rather than erroring out,
since the reader thread's exception path never reaches the clean EOF
signal `EofTriggeredShutdownInputStream` needs to trigger this ADR's
shutdown sequence - a bad failure mode for the next person who tries
this interactively on Windows and just sees it hang with no explanation.
This was an environment incompatibility in the test script, not a defect
in the fix itself. Genuinely fixed, not just documented or guarded
around: the script's stdin mechanism was rewritten from a named pipe to
plain-file redirection (`< file`), which reaches the same genuine EOF
the instant its content is exhausted, needs no named-pipe support, and
is identical POSIX shell behavior on Linux and Windows Git Bash alike -
removing the incompatibility rather than detecting and avoiding it.
Confirmed passing as literally shipped, no external workaround, on this
Windows machine, against both the unreachable-host scenario and (via a
temporary, non-committed variant pointed at real DB2) the real-backend
scenario above - both produced "EOF SHUTDOWN RACE TEST PASSED". CI
(Linux) was already unaffected by the original bug and remains so; the
rewrite uses nothing platform-specific, so the Linux path is unchanged
in behavior, just simpler (no feeder subshell or fifo cleanup).

## Update 2026-08-20: replaced the fixed-delay heuristic with an id-based deterministic drain

The `DISPATCH_SETTLE_DELAY`/`OUTBOUND_FLUSH_SETTLE_DELAY` mitigation above was
explicit, in its own "Consequences" section, about being "a mitigation with a
documented residual limit, not a mathematical guarantee." That limit stopped
being theoretical: promoting `devel` to `main` (PR #14) hit a genuine CI
failure on `scripts/eof-shutdown-race-test.sh` - the exact regression this
ADR exists to prevent - on a run using the identical, already-merged fix
commit. A `gh run rerun` of the same job passed, confirming it as
intermittent, not a code change between runs: on that contended runner, the
scheduler handoff from "request read" to "tool dispatched" occasionally took
longer than the 1s `DISPATCH_SETTLE_DELAY` (itself already widened once from
300ms for the same reason).

**Root cause of the residual flakiness.** The count-based tracker
(`InFlightToolCallTracker`) only learned a call was in flight when Spring AI
actually dispatched it to the wrapped `ToolCallback` - a point downstream of
a reactor scheduler handoff whose duration this project doesn't control.
`DISPATCH_SETTLE_DELAY` was a guess at an upper bound for that handoff, not
an observation of it. Any guess has a runner contended enough to exceed it.

**The fix: track by request id, marked directly on the raw bytes, not on
dispatch.** Two new stream wrappers replace the tool-callback-level
decorator:
- `RequestIdTrackingInputStream` wraps `System.in` (innermost, with {@code
  EofTriggeredShutdownInputStream} still outermost) and marks a request's id
  pending in the new `InFlightRequestTracker` the instant its line is fully
  read - synchronously, inside the same `read()` call the SDK itself
  observes those bytes in. A single `read()` call cannot both deliver a
  line's trailing bytes and return EOF (`InputStream`'s own contract), so
  marking always happens before any later call can observe the EOF that
  follows it. No scheduler handoff sits between "bytes read" and "marked" -
  there's nothing left to guess a delay for.
- `ResponseIdTrackingOutputStream` wraps `System.out` and clears that id the
  instant a response line carrying it is written - a strictly later, more
  accurate signal than "the tool method returned" (which is what the
  count-based tracker's decrement actually meant): a returned tool method's
  response still had to be serialized and handed to the SDK's own outbound
  scheduler before this project's shutdown check would have been correct to
  proceed.

`InFlightRequestTracker` replaces `InFlightToolCallTracker`; both fixed
delays are gone from `StdioLifecycleConfig` - `awaitDrain` now blocks only
on the real per-id signal, bounded by the existing `shutdownGracePeriod` for
a genuinely stuck call, the same role that bound always had. The old
count-based classes (`InFlightToolCallTracker`, `InFlightTrackingToolCallback`)
and their tests are deleted, not kept alongside the new mechanism - they're
fully superseded, and every one of this server's request types is now
covered uniformly (the old decorator only wrapped `ToolCallback`, so a slow
non-tool request, e.g. `resources/read`, was never actually tracked at all).

**Resource cost.** Both wrappers copy already-flowing bytes into a small
per-stream line buffer (`ByteArrayOutputStream`, cleared every line - never
larger than one JSON-RPC message) and parse each complete line once with
Jackson to read its `id`/`method` fields - equivalent order-of-magnitude
work to what the SDK's own line-oriented reader already does on the same
bytes. `InFlightRequestTracker`'s pending set holds one entry per
concurrently-outstanding request (a `ConcurrentHashMap`-backed set,
effectively always small for a single stdio client) and adds no new
threads; the only polling loop (`awaitDrain`'s 20ms `Thread.sleep`) runs
solely during the shutdown window, never during ordinary operation. Not
expected to be measurable against JVM startup and DB2 I/O costs already
dominating this process's footprint.

**Verification.** `scripts/eof-shutdown-race-test.sh` run 8 consecutive
times against the packaged jar, all passing (vs. the previous version,
which had just been observed to fail 1 run in a small sample on real CI).
Full unit suite green, including new coverage for `InFlightRequestTracker`,
`RequestIdTrackingInputStream`, and `ResponseIdTrackingOutputStream`
(id-tracking correctness, notification lines correctly left untracked,
byte-at-a-time delivery, and a regression guard against
`FilterOutputStream`'s default `write(byte[], int, int)` double-processing
bytes through `write(int)` if routed through `super.write(...)` instead of
the wrapped stream directly).

## Note 2026-08-30: an adjacent but distinct defect found during ADR 0033's live verification, and fixed the same day

While reproducing ADR 0033's own concurrency finding, two concurrent calls
to the *same* tool name (any tool - reproduced with `explainQuery`,
`listSchemas`, `getCapabilities`) were found to reliably drop one response
with `"Failed to enqueue message"` from the same SDK transport class this
ADR already investigates (`StdioServerTransportProvider`) - but confirmed
present even with stdin held genuinely open the whole time, so this was
**not** this ADR's stdin-EOF race. Filed as
issue #29.

Root-caused by reading the SDK's own published sources directly
(`mcp-core:2.0.0`, confirmed unchanged in `2.0.1`): `StdioServerTransportProvider`
writes every outbound response through a single `Sinks.many().unicast()`
sink shared by the whole session - a Reactor sink type documented as safe
for only one producer thread at a time, and `sendMessage()` is called from
whatever thread each tool call's completion lands on. Two app-level
mitigations were tried and empirically ruled out (a per-tool lock and a
global, server-wide lock around tool execution, neither closed the race -
the actual write happens in an async continuation downstream of
`ToolCallback.call()` returning, a thread hop this project's own code has
no hook into).

**Fixed** by replacing the SDK's transport with this project's own
`SerializedStdioServerTransportProvider` (implementing the public
`McpServerTransportProvider`/`McpServerTransport` interfaces directly,
since the SDK's own transport class is a private inner class with no
extension point) - same inbound-processing shape as the SDK's, but
`sendMessage` writes directly and synchronously under a plain lock instead
of through a Reactor sink, so there is no unsynchronized enqueue step left
to race on. Fully compatible with this ADR's own EOF-shutdown-drain
mechanism and the id-tracking streams below - both operate on raw bytes
crossing the given `InputStream`/`OutputStream`, regardless of which class
reads/writes them. Verified: 21/21 concurrent-call reproduction runs across
three tools (`listSchemas`, `getCapabilities`, `explainQuery`) show zero
dropped responses, and `scripts/eof-shutdown-race-test.sh` (this ADR's own
regression test) still passes 5/5 against the new transport.

**Filed upstream** (2026-08-31): this is a genuine, already-known bug in
the SDK itself, not something specific to how this project uses it -
[modelcontextprotocol/java-sdk#686](https://github.com/modelcontextprotocol/java-sdk/issues/686)
is the exact same server-side bug (identical stack trace), already filed
and open since 2026-02-20, with a stalled, unmerged fix attempt
([PR #834](https://github.com/modelcontextprotocol/java-sdk/pull/834),
opened 2026-02-24, reviewed same day, no activity since March, now has
merge conflicts). That PR takes a different approach - `emitNext()` with
`Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1))`, retrying
emission on the same sink instead of bypassing it - which still has a
narrow, non-zero failure mode under sustained contention (the busy-loop's
own timeout) that this project's plain-lock approach doesn't. Posted this
project's root-cause writeup and fix approach as a comment on #686 to
help get that PR unstuck.
[modelcontextprotocol/java-sdk#875](https://github.com/modelcontextprotocol/java-sdk/issues/875)
is the client-side analog of the identical `unicast()`-sink misuse
pattern, independently found. Worth revisiting
`SerializedStdioServerTransportProvider` if/when an official fix ships in
a released SDK version - this project's own replacement is a real,
verified fix but is now something this project owns and has to keep in
sync with the SDK's own interface evolution, a cost an eventual upstream
fix wouldn't carry.

## Related decisions
ADR 0016 (graceful shutdown - the mechanism this ADR corrects a gap in),
ADR 0022 (the live-DB2 verification session that surfaced this finding),
ADR 0010/0011/0012 (audit/metrics/span instrumentation - used as the
independent evidence, in the second reproduction, that a tool call had
genuinely completed even though its response was still dropped).
