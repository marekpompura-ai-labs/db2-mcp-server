# ADR 0048: Do not distribute the IBM JDBC driver; load it from outside the jar

## Status
Accepted.

## Date
2026-10-09.

## Context

The `1.0.0` build produced an application archive containing the IBM Data
Server Driver for JDBC and SQLJ. This is a verified fact, not an inference:
`BOOT-INF/lib/jcc-12.1.5.0.jar` is present in `db2-mcp-server-1.0.0.jar`,
which is 47 MB, of which about 6.4 MB is the driver.

**There is no known external distribution of such an archive, and the
distinction matters.** The repository is private, the `v1.0.0` release
carries no binary assets, no jar was ever committed, there is one
collaborator, and the CI jar artifact expired under its one-day retention.

That is evidence of no known distribution, not proof that none occurred: the
artifact's expiry today says nothing about whether anyone downloaded it while
it existed, and none of these facts has been independently audited. Stated at
this strength deliberately, after a 2026-10-10 review pointed out that an
earlier draft asserted "nothing reached anyone" on exactly this evidence.

Either way the defect is the same, and so is the fix: the build would have
shipped the driver the moment an archive was handed to someone, which was the
next planned step. It was caught inside that window.

It got there the ordinary way. [ADR 0003](0003-driver-sourcing.md) chose to
resolve the driver from Maven Central and pin its version, which is good for
build reproducibility. The dependency was declared with no `<scope>`, so it
defaulted to `compile`, and `spring-boot-maven-plugin`'s repackage goal puts
compile-scope dependencies into `BOOT-INF/lib`. Nobody decided to
redistribute IBM's driver; the default did it.

Two separate reasons to stop.

**The licensing position was never established.** The licensing evidence
collected for this project obtained IBM's License Information document
(**L-HJHH-A2W6TH**, 25 June 2026) for JCC 12.1.5. That document defines
"Redistributables" narrowly: exactly `db2jcc4.jar` and `sqlj4.zip`, in
object-code form, only as part of a value-added application developed using
the Program, with copyright and notice files intact, and under an end-user
agreement at least as protective of IBM as IBM's own. It also names the
International Program License Agreement **i125-3301-15** as the governing
agreement, and that document was **not** obtained.

So for the artifact this project actually shipped, three things are
unresolved at once: whether `jcc-12.1.5.0.jar` as published on Maven Central
is the same artifact as the named Redistributable `db2jcc4.jar` (not
established); whether the Redistributables conditions were met (the Maven
artifact contains no `NOTICES` file to preserve — it holds compiled classes
and `META-INF/{MANIFEST.MF, services/java.sql.Driver}` and nothing else);
and what the unobtained master agreement requires. None of this is a legal
conclusion, and this ADR does not draw one. The point is narrower and
sufficient: **the project was relying on a redistribution permission it had
never read.** Not shipping the driver removes the question instead of
answering it.

**It is also the wrong engineering default.** Db2 connectivity is
environment-specific. Which driver version an enterprise is entitled to,
whether z/OS or i connectivity needs separate licence activation files, and
which driver its DBAs have actually certified are all site decisions.
Pinning one copy inside the jar quietly overrides all of them, and an
operator who needs a different driver has to rebuild the application to get
it.

## Decision

**No IBM driver is distributed in any artifact this project produces.** The
operator supplies it.

Two build changes, both required:

1. The `com.ibm.db2:jcc` dependency is declared **`<scope>test</scope>`**.

   It was `provided` in the first version of this decision. A 2026-10-10
   review asked for a test-only arrangement if no compile-time dependency
   existed, and none does: **`src/main/java` contains zero references to
   `com.ibm.db2`.** The adapter is written entirely against `java.sql` and
   Spring JDBC; the driver is reached through `DriverManager`/Hikari by class
   name, which is why `provided` was never buying anything. Exactly three
   test classes name it, so `test` is the narrowest scope that still lets the
   live-Db2 suite ([ADR 0044](0044-automated-live-db2-integration-test-suite.md))
   run against the real driver.

2. An explicit `<excludes>` entry on the repackage goal removes it from
   `BOOT-INF/lib`.

**Step 2 is not redundant.** `provided` scope alone does not keep a
dependency out of a Spring Boot fat jar: repackage bundles provided-scope
dependencies by design, on the assumption that "provided" means "the servlet
container will have it" — an assumption a self-contained executable jar
breaks. Changing the scope and stopping there would have looked correct and
shipped the driver anyway. This was verified by building both ways and
inspecting the archive, not reasoned about.

Because the driver is no longer inside the jar, the operator needs a
supported way to put it on the classpath. The repackage `<layout>` is
therefore `ZIP`, which makes `PropertiesLauncher` the manifest `Main-Class`.
`PropertiesLauncher` honours `-Dloader.path`; `JarLauncher`, the previous
launcher, has no equivalent. Operator instructions are in
[`docs/operations/external-jdbc-driver.md`](../operations/external-jdbc-driver.md).

The licence change that accompanies this release is recorded separately in
`LICENSE-HISTORY.md` and `NOTICE`; it is not the subject of this ADR.

## Consequences

**The application no longer starts without a driver, and says so.** With no
`-Dloader.path`, startup fails during context refresh with
`IllegalStateException: Cannot load driver class: com.ibm.db2.jcc.DB2Driver`.
This is the behaviour we want: a missing driver is a deployment error that
surfaces at startup, in one obvious message, not a `ClassNotFoundException`
on the first tool call. Verified by running the packaged jar without the
flag.

**Verified, not assumed.** `scripts/verify-no-jcc.sh` is wired into
`scripts/verify-release.sh` and into CI, and walks the distribution set
**recursively**:

1. **Every archive under `target/`**, found by walking rather than listing
   one directory - so a release ZIP, a WAR or a sources jar added later
   cannot escape the gate.
2. **Driver artifacts by name** at every nesting level: `jcc*`, `db2jcc*`,
   `sqlj*`, and the licence-activation jars IBM's License Information names.
3. **Driver classes** under `com/ibm/db2/**`, at every nesting level.
4. **Nested `.jar`, `.war`, `.ear` and `.zip` entries**, opened and searched
   the same way, to a bounded depth, with a read budget so a pathological
   archive cannot turn a release check into a hang.

It **fails closed**: an archive that cannot be opened, at any depth, is a
failure rather than a pass.

**This check has been wrong twice, and both times the artifact was what
caught it, not the code review.** Version one's name pattern required
`jcc.jar` exactly, so it passed an archive containing `jcc-12.1.5.0.jar`.
Version two fixed that but opened only one nested level and ignored nested
ZIP entries, so it passed `release.zip -> app.jar -> lib/innocent.jar ->
DB2Driver.class` and `release.zip -> payload.zip -> lib/jcc.jar` - both
reproduced by the 2026-10-10 review. Hence
`scripts/verify-no-jcc-cases.py`: 15 committed containers, run by
`verify-no-jcc.sh --self-test` in CI and in release verification, so the gate
is tested rather than trusted.

It deliberately does **not** match the string `com.ibm.db2.jcc`, which
legitimately appears in configuration and in this project's own error
message. That is one of the 15 cases, asserting a pass. `scripts/smoke-test.sh` now launches the jar
with `-Dloader.path` and keeps asserting the connectivity check, so a
regression from `ZIP` back to `JarLauncher` — which would silently break
every operator's `loader.path` — fails the smoke test instead of shipping.

**The jar is smaller**: 41 MB against 47 MB.

**The build still downloads the driver.** `test` scope is still a declared
Maven dependency, so running the tests resolves `jcc` from Maven Central into
the builder's local repository. This ADR changes what this project
*distributes*, not what Maven fetches to test against. Anyone who must avoid
that resolution entirely has to install the artifact into their own
repository manager from their IBM entitlement. Stated here because it is easy
to assume a non-compile scope means "never fetched", and it does not.

**`spring-boot:run` now needs the driver too.** With `test` scope the driver
is off the run classpath as well, so running from source takes
`-Dspring-boot.run.additionalClasspathElements=<driver>`, documented in the
operations guide. This is a real cost of tightening the scope and is the main
thing `provided` was buying.

**The driver has left the SBOM, and that is correct but has a consequence.**
`cyclonedx-maven-plugin` excludes test scope by default, so the component
count went 111 → 110 and `com.ibm.db2:jcc` no longer appears. The SBOM now
describes what this project delivers, which is the right scope for it. But
the CI dependency-vulnerability gate (Trivy, scanning that SBOM) therefore no
longer sees the driver either. **Vulnerability monitoring for the driver is
the operator's responsibility**, as is supplying and patching it; that is
stated in the operations guide rather than left as a silent gap.

**One more thing an operator must now get right.** This is a real cost, and
the mitigation is that it fails loudly and immediately rather than subtly.

## Alternatives considered

**Keep shipping it and add the notices the LI requires.** Rejected: it
assumes the Redistributables clause applies to this artifact, which depends
on the `jcc-*.jar` / `db2jcc4.jar` identity question that is unresolved, and
on the unobtained master agreement. Building compliance machinery on an
unread permission is worse than not needing the permission.

**`provided` scope only, no exclusion.** Rejected on evidence: it does not
work. See above.

**Explode the jar and let operators manage `-cp` by hand.** Rejected: it
pushes classpath assembly onto every operator and loses the single-artifact
deployment model, to avoid one documented system property.

**Make the driver optional and degrade without it.** Rejected: every tool
this server exposes needs Db2. A server that starts without a driver and
fails each tool call individually is strictly worse than one that refuses to
start.
