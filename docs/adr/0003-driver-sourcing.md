# ADR 0003: Source the DB2 JDBC driver from Maven Central, pinned to a validated version

## Status
Accepted — sourcing decision still in force; its licensing claims and the
compile-scope/bundling consequence are corrected and partly superseded by
[ADR 0048](0048-external-jdbc-driver.md). See the amendment at the end.

## Context
The server needs IBM's DB2 JDBC driver (`jcc`, the Type 4 "IBM Data Server
Driver for JDBC and SQLJ"). Two things had to be established before treating
it as a normal build dependency:

1. **Availability** — `com.ibm.db2:jcc` is published on Maven Central and
   actively maintained by IBM (recent releases include `12.1.4.0` in March
   2026 and `12.1.5.0` in June 2026), so it can be declared as an ordinary
   Maven dependency with no manual jar handling.
2. **License terms** — the jcc4 driver family bundles a license
   (`db2jcc_license_cu.jar`) that permits free, no-charge use for connecting
   to **DB2 LUW** (Linux/Unix/Windows). Connecting to DB2 for z/OS or IBM i
   requires a separate, paid license file not included in the free
   download. This project targets DB2 LUW, so the bundled free license
   applies without any additional licensing step.

## Decision
Depend on `com.ibm.db2:jcc` directly from Maven Central, pinned to
`11.5.9.0` — a version already validated against the target DB2 LUW
instance — rather than always tracking the latest release.

```xml
<dependency>
    <groupId>com.ibm.db2</groupId>
    <artifactId>jcc</artifactId>
    <version>11.5.9.0</version>
</dependency>
```

The version pin is a compatibility decision, not a licensing or
availability workaround: the free-redistribution terms apply across the
jcc4 driver line generally, so any recent version would be licensable —
`11.5.9.0` was chosen because it's the version already confirmed to work
correctly against this DB2 server.

## Consequences
- **Fully standard, portable build.** No manual driver installation step,
  no local Maven install, no undocumented prerequisite on the developer's
  machine — `mvn package` resolves the driver like any other dependency.
  This also means the build works unmodified in CI or on another
  developer's machine.
- **Version bumps are a deliberate, reviewable change** (a one-line version
  bump in `pom.xml`), not a re-run of a manual sourcing process — moving to
  a newer `jcc` release only requires re-validating compatibility against
  the target DB2 server first.
- **License scope is tied to the target platform, not the version.** If
  this server were ever pointed at DB2 for z/OS or IBM i instead of LUW,
  the free license would no longer apply and the additional license file
  would need to be sourced and added — that's a new decision, not an
  extension of this one.

---

## Amendment, 2026-10-09 — licensing claims corrected, sourcing decision partly superseded

Three statements above did not survive the licensing review that preceded
release `1.0.1`. They are corrected here rather than edited away, because
what this ADR got wrong is part of why [ADR 0048](0048-external-jdbc-driver.md)
exists.

**1. "The jcc4 driver family bundles a license (`db2jcc_license_cu.jar`)
that permits free, no-charge use for connecting to DB2 LUW."** Not true of
the artifact this project actually resolves. `com.ibm.db2:jcc:12.1.5.0` from
Maven Central was opened and inspected: it contains compiled classes and
`META-INF/{MANIFEST.MF, services/java.sql.Driver}`, and **nothing else** —
no licence jar, no `NOTICES` file, no licence text. The statement described
IBM's downloadable driver package, not the Maven artifact, and the two were
conflated.

**2. "The free-redistribution terms apply across the jcc4 driver line
generally, so any recent version would be licensable."** This was an
unsupported licensing conclusion, and it is the one that mattered, because
release `1.0.0` relied on it to ship the driver inside the application jar.
IBM's License Information document for this exact version
(**L-HJHH-A2W6TH**, 25 June 2026) was subsequently obtained. It defines
"Redistributables" as exactly `db2jcc4.jar` and `sqlj4.zip`, under
conditions including preserving copyright and notice files — and whether the
Maven artifact `jcc-12.1.5.0.jar` *is* the named `db2jcc4.jar` is **not
established**. The document also names the International Program License
Agreement `i125-3301-15` as governing, which was **not obtained**.

**3. "License scope is tied to the target platform, not the version"**, with
the LUW / z-OS / i distinction treated as settled. The LI document does not
address it: the strings `z/OS`, `Db2 for i`, `LUW`, `Linux`, `UNIX` and
`Windows` each occur **zero** times in it. The widely repeated convention
that z/OS and i connectivity needs separate activation files remains an
**unverified lead**, not a finding this project can cite.

**None of the above is a legal conclusion, and no conclusion is drawn here.**
The correction is narrower: this ADR asserted licensing facts it had not
verified, and a later release acted on them.

**What still stands.** The *sourcing* decision — resolve the driver from
Maven Central as an ordinary Maven dependency, pinned to a version validated
against the target server — remains in force for building and testing. The
pinned version is now `12.1.5.0`, carried in the `db2-jcc.version` property;
the `11.5.9.0` in the snippet above is the value at the time this ADR was
written.

**What is superseded.** The dependency is no longer `compile` scope and the
driver is no longer distributed. It is `test` scope and explicitly excluded
from the repackaged archive, and the operator supplies it. See
[ADR 0048](0048-external-jdbc-driver.md) and
[`docs/operations/external-jdbc-driver.md`](../operations/external-jdbc-driver.md).
