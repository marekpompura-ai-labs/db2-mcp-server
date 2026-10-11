# Source availability for distributed third-party components

**The Source Code for each component listed below is available under the
Eclipse Public License 2.0.** A copy of that Agreement accompanies this
distribution at
[`third-party-licenses/EPL-2.0.txt`](third-party-licenses/EPL-2.0.txt).

**How to obtain it.** Each component's source is published by its own
project to **Maven Central**, the medium customarily used for software
exchange in the Java ecosystem, as a `sources` classifier artifact beside
the binary this project distributes. The exact artifacts are pinned below.
They were resolved and checksummed on 2026-10-10, so this statement names
files that were confirmed to exist rather than ones assumed to.

| Component | Source artifact | Bytes | SHA-256 |
|---|---|---|---|
| `ch.qos.logback:logback-classic:1.5.38` | `logback-classic-1.5.38-sources.jar` | 251,268 | `7f0a30657cb7b63379686419924e73851fa7e5159e0c3d7c653646030a142643` |
| `ch.qos.logback:logback-core:1.5.38` | `logback-core-1.5.38-sources.jar` | 531,931 | `682355ce7b96b8c6350ae20df86fe13ea262ef6b5d863833f26da1f700d6800a` |
| `jakarta.annotation:jakarta.annotation-api:3.0.0` | `jakarta.annotation-api-3.0.0-sources.jar` | 36,220 | `142dfd2343429df2aac3e1cfacfacc21c8393112c0e0280d3628648d9b612470` |

Any of the three can be retrieved with Maven alone:

```bash
mvn dependency:get -Dartifact=ch.qos.logback:logback-classic:1.5.38:jar:sources
mvn dependency:get -Dartifact=ch.qos.logback:logback-core:1.5.38:jar:sources
mvn dependency:get -Dartifact=jakarta.annotation:jakarta.annotation-api:3.0.0:jar:sources
```

Or downloaded directly:

- <https://repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.5.38/logback-classic-1.5.38-sources.jar>
- <https://repo1.maven.org/maven2/ch/qos/logback/logback-core/1.5.38/logback-core-1.5.38-sources.jar>
- <https://repo1.maven.org/maven2/jakarta/annotation/jakarta.annotation-api/3.0.0/jakarta.annotation-api-3.0.0-sources.jar>

The table covers the versions distributed in release 1.0.1. Update this
statement when changing any of these dependencies.

## If a recipient cannot reach Maven Central

Write to **marek.pompura.it@gmail.com** and the source archives listed above
will be supplied directly, at no charge beyond the cost of delivery. This is
offered so that the "reasonable manner" requirement does not depend on a
recipient's network access to a public repository.

## Licence boundary

This statement supplies source-location information for the three EPL-2.0
components listed above. Those components retain their own licence and
source rights. PolyForm Strict governs original project material only.
No third-party component has been modified.

The third-party elections are recorded in
[`LICENSE-ELECTIONS.md`](LICENSE-ELECTIONS.md). Other accompanying licence
and attribution material is in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and
[`third-party-licenses/`](third-party-licenses/).
