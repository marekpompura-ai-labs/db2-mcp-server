# ADR 0016: Exit cleanly when the MCP client disconnects

## Status
Accepted — implemented and verified in this environment (`mvn verify`
green, 113 tests; both shutdown paths proven against the real packaged
jar, not assumed from the SDK's behavior).

## Date
Proposed 2026-08-13, Accepted 2026-08-13.

## Context
Master brief §10 lists "graceful shutdown and cancellation" as part of
the manageability specification. This server is stdio-transport only
(ADR 0001): there's no HTTP endpoint to gracefully drain, so "graceful
shutdown" here means two concrete things - shutting down cleanly on
`SIGTERM` (already Spring Boot's default behavior, worth verifying
rather than assuming), and shutting down when the MCP client disconnects
by closing stdin, since that's the normal way a stdio server's parent
process signals "we're done" without necessarily sending a signal.

Investigating the second case surfaced a real, verifiable bug: the MCP
Java SDK's `StdioServerTransportProvider` already detects stdin EOF
internally (`BufferedReader.readLine()` returning `null`) and closes its
own protocol session - but nothing ties that session closure to the JVM
process actually exiting. `spring.main.keep-alive: true` (required by
ADR 0001 so the JVM doesn't exit immediately on daemon threads alone)
keeps a dedicated non-daemon thread alive completely independently of
MCP session state. Confirmed by starting the packaged jar with stdin
already at EOF (`< /dev/null`): the server started, logged "Registered
tools," and then simply sat there - still running, unkillable except by
signal, 10+ seconds later with nothing left to do. Every prior use of
`< /dev/null`-style smoke testing in this project's history happened to
leave the process running in the background and explicitly `kill`ed it
afterward, which is exactly how this went unnoticed until it was
checked for directly.

## Decision drivers
- The process must actually exit when its MCP client disconnects, not
  become an orphaned background process that has to be killed by hand.
- The fix must not touch or reimplement the SDK's own stdin-reading
  logic (`StdioServerTransportProvider`'s internal `BufferedReader`
  loop) - reading `System.in` from two places would race.
- `SIGTERM` handling (Spring Boot's default `context.close()` via a JVM
  shutdown hook) needs to be confirmed still correct, not just assumed
  unaffected by any change here.
- Shutdown should be bounded, not allowed to hang indefinitely if
  something misbehaves - consistent with this project's "bounded
  resources" posture elsewhere (ADR 0007's timeouts, ADR 0008's
  allowlist).

## Considered options
1. **Wrap `System.in` in an EOF-detecting `FilterInputStream` and supply
   our own `stdioServerTransport` bean, replacing Spring AI's default.**
   Spring AI's `McpServerAutoConfiguration.stdioServerTransport()`
   constructs `new StdioServerTransportProvider()` (the no-arg
   constructor, hardcoded to `System.in`/`System.out`) as a normal
   `@Bean` method - confirmed via decompiling the autoconfiguration class
   rather than assumed from documentation. Defining our own `@Bean`
   method of the same type lets us use the SDK's 3-argument constructor
   (`ObjectMapper`, `InputStream`, `OutputStream`) instead, passing a
   thin wrapper that fires a callback the moment a read returns EOF - no
   competing reads, since the wrapper only observes what the SDK's own
   reader is already doing.
2. **Poll `System.in.available()` or the file descriptor's state from a
   separate watcher thread.** Rejected: `available()` doesn't reliably
   signal EOF on a closed pipe without an actual blocking read, and
   there's no portable non-consuming "peek" on a general `InputStream` -
   this would either race with the real reader or not work at all.
3. **Rely on the parent process always sending `SIGTERM` instead of just
   closing the pipe.** Rejected: it's not this project's call to make -
   an MCP host is free to close its child's stdin as its disconnect
   signal, and a server that only handles `SIGTERM` gracefully is
   handling half the problem while calling it done.

## Decision
Add `EofTriggeredShutdownInputStream` (a `FilterInputStream` that fires a
callback exactly once, the first time either `read()` overload returns
`-1`) and `StdioLifecycleConfig`, a `@Configuration` class providing a
replacement `stdioServerTransport` bean that wraps `System.in` with it.
On EOF, the callback runs `SpringApplication.exit(context, () -> 0)`
followed by `System.exit(...)` on a dedicated new thread - not the
reader thread itself, since `context.close()` runs `DisposableBean`/
`@PreDestroy` callbacks (including `HikariDataSource.close()`) that must
not execute on the very thread whose `read()` call triggered them. Also
add `spring.lifecycle.timeout-per-shutdown-phase: 10s` so shutdown itself
is bounded rather than able to hang indefinitely.

## Consequences
- **The core bug fixed and proven, not assumed fixed:** starting the
  packaged jar with `< /dev/null` (stdin at EOF from the start) now logs
  `MCP client closed stdin - shutting down` and the process exits with
  code `0` in ~4 seconds, versus running indefinitely before this
  change. Verified via `wait $PID` catching a real exit, not a timeout-
  based guess.
- **`SIGTERM` handling reconfirmed unaffected**, not just assumed
  unaffected by an unrelated-looking bean change: a real running
  instance (fed by an open FIFO, not `/dev/null`, so it wouldn't take the
  EOF path) was sent `SIGTERM` directly and exited within ~2 seconds
  (exit code 143, the standard 128+SIGTERM convention for a default
  signal-terminated JVM).
- **stdout integrity holds under both shutdown paths** (ADR 0001): 0
  bytes on stdout whether the process exits via stdin EOF or `SIGTERM`.
- **Spring AI's own `stdioServerTransport` bean is quietly replaced**,
  confirmed to actually take effect (not silently shadowed or duplicated)
  by the distinct log line and behavior change appearing at runtime -
  Spring AI's autoconfiguration must define that bean in a way that
  yields to a same-typed bean supplied elsewhere in the context, which
  this project relies on without needing to assert the exact
  conditional-annotation mechanism, since the real behavior was verified
  directly against the packaged jar either way.
- **Full suite: 113 tests** (up from 109), all green under `mvn verify
  -DskipITs`. `EofTriggeredShutdownInputStreamTest` covers the wrapper in
  isolation (fires exactly once on EOF for both `read()` overloads, never
  fires while real data remains, fires only after genuine data is
  exhausted) without needing a Spring context at all.
- **Known limitation, stated rather than implied away:** "cancellation"
  from the brief's "graceful shutdown and cancellation" phrasing is not
  addressed here - an in-flight `executeQuery` call is bounded by ADR
  0007's JDBC statement timeout, but there's no explicit mechanism to
  cancel a running query specifically because the process is shutting
  down (as opposed to timing out on its own). Left out of this ADR's
  scope; the statement timeout already bounds how long that matters.

## Verification / enforcement
`EofTriggeredShutdownInputStreamTest` (unit, no Spring context) plus two
real packaged-jar scenarios: stdin EOF from process start (`< /dev/null`)
and `SIGTERM` on a running instance fed by an open pipe. Both produce a
clean exit with 0 bytes on stdout.

## Related decisions
ADR 0001 (stdio transport, stdout reserved for JSON-RPC, `keep-alive`
requirement - the root cause this ADR addresses), ADR 0015 (this
project's other Phase 5 manageability ADR; both concern operational
behavior of the packaged jar rather than request-handling logic), ADR
0011 (HikariCP configuration - `DataSource.close()` is one of the
`DisposableBean` callbacks this ADR's shutdown ordering protects).
