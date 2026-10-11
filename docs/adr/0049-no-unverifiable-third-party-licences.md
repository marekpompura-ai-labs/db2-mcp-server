# ADR 0049: Do not distribute a component whose licence cannot be verified

## Status
Accepted.

## Date
2026-10-10.

## Context

[ADR 0048](0048-external-jdbc-driver.md) stopped distributing the IBM JDBC
driver. The reason given there was narrow and deliberate: **the project was
relying on a redistribution permission it had never read.** Not shipping the
driver removed the question instead of answering it.

That reasoning does not only apply to IBM. It was worth asking whether
anything else in the shipped artifact was in the same position, and the
answer was yes.

Of the 106 third-party libraries the `1.0.1` candidate bundled, the licence
declarations broke down as:

| Declaration | Count |
|---|---|
| Apache-2.0 | 89 |
| MIT | 6 |
| EPL-2.0 **or** LGPL-2.1-only | 2 |
| EPL-2.0 **or** GPL-2.0-with-classpath-exception | 1 |
| LGPL-2.1-only **or** Apache-2.0 | 1 |
| CC0-1.0 **or** BSD-2-Clause | 1 |
| BSD-3-Clause | 1 |
| MIT-0 | 1 |
| **BSD-4-Clause** | **1** |
| **"BSD licence"** (not an SPDX identifier) | **1** |
| **"Public Domain"** (no URL, no text) | **1** |

The first eight rows are fine. They are standard licences with explicit,
unconditional redistribution rights, and the one obligation they do impose —
preserving attribution notices — is now assembled from the artifacts
themselves and checked in CI
([`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md)). Dual-licensed
components need an election recorded, which is a decision, not an unknown.

The last three rows are a different kind of thing.

**`org.antlr:ST4:4.3.4` — declared BSD-4-Clause.** Not BSD-3-Clause. The
declaration is the POM's; the advertising-clause text below comes from the
licence text captured for that component during the Phase 1 review, and the
POM declaration **alone** would not have been proof of it. The fourth clause
is the *advertising clause*:

> All advertising materials mentioning features or use of this software must
> display the following acknowledgement: This product includes software
> developed by the organization.

That is an ongoing obligation on **marketing material**, not just on the
distributed files. For a project intended to be offered commercially, that
reaches well past the repository, and it is exactly the sort of term that
gets discovered late.

**`org.antlr:antlr-runtime:3.5.3` — declared only as `"BSD licence"`.** Not
an SPDX identifier. There is no licence text in the artifact and none in the
SBOM, so **which** BSD variant governs it is not established *from what the
build can see* — and given that its sibling `ST4` is declared BSD-4-Clause,
assuming the harmless 3-clause variant would be an assumption, not a finding.

**Correction, 2026-10-10.** An earlier draft of this ADR implied that
upstream verification was impossible. It is not: ANTLR 3 publishes a
three-clause BSD licence at <https://www.antlr3.org/license.html>, and AOP
Alliance publishes a public-domain declaration at
<https://aopalliance.sourceforge.net/>. The accurate claim is narrower and
still sufficient: neither was established from the artifact or the SBOM, and
pinning an exact-release attribution for each would have been work spent on
dependencies this project does not use. Removing them was the cheaper correct
answer, not the only possible one.

**`aopalliance:aopalliance:1.0` — declared `"Public Domain"`**, with no URL
and no text anywhere. A public-domain dedication is not a licence, and its
effect varies by jurisdiction. The terms on which it may be redistributed
are not established.

**Where they came from matters.** Neither ANTLR component is a deliberate
dependency of this project:

```
spring-ai-starter-mcp-server
  └─ spring-ai-model
       └─ spring-ai-template-st
            └─ org.antlr:ST4:4.3.4
                 └─ org.antlr:antlr-runtime:3.5.3
```

`spring-ai-template-st` is Spring AI's **StringTemplate-based prompt
template** support. This project renders no prompts. It is an MCP *server*
that exposes typed tools and validates SQL; prompt templating is a client
concern. Nothing in `src/main/java` imports anything from it.

`aopalliance` arrives under `micrometer-tracing-bridge-otel`. It is four
interfaces, and Spring Framework ships its own copies of them inside
`spring-aop`, which is already on the classpath.

So the project was carrying an unverifiable licence, an unknown BSD variant,
and a standing obligation on its own advertising — to support a feature it
does not use, and a jar whose classes it already has.

## Decision

**A component whose redistribution terms cannot be verified is not
distributed.** This generalises ADR 0048 from "the IBM driver" to a rule.

Applied now by excluding three components:

```xml
<!-- on spring-ai-starter-mcp-server -->
<exclusion>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-template-st</artifactId>
</exclusion>

<!-- on micrometer-tracing-bridge-otel -->
<exclusion>
  <groupId>aopalliance</groupId>
  <artifactId>aopalliance</artifactId>
</exclusion>
```

Excluding `spring-ai-template-st` removes it and both ANTLR components: four
libraries, 106 → 102.

**The rule is "unverifiable", not "third-party".** Not distributing any
third-party library at all was considered and rejected — see Alternatives.
The 88 Apache-2.0, 6 MIT, 1 MIT-0 and 1 BSD-3-Clause components have
explicit redistribution grants that have been read, and their notice
obligations are satisfied and enforced. Removing them would buy nothing
legally and cost a great deal operationally.

## Consequences

**No shipped component now has an unverified or unusual licence
declaration.** The resulting profile is 88 Apache-2.0, 6 MIT, 1 MIT-0, 1
BSD-3-Clause and 5 dual-licensed components awaiting an election
([`LICENSE-ELECTIONS.md`](../../LICENSE-ELECTIONS.md)). Zero non-SPDX
declarations, zero public-domain dedications, **and no advertising clause** —
so marketing this project imposes no acknowledgement obligation.

**A standard identifier is not compliance, and this ADR does not claim it
is.** Knowing that 88 components say "Apache-2.0" establishes what they
require, not that the requirement is met. Meeting it is separate work, done
in [`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md) and checked on
every build — 47 of the 102 shipped libraries carry no licence or notice
material of their own, which no licence identifier would have revealed.

**Verified at runtime, not just at compile time.** A removal like this is
exactly the kind that passes `mvn verify` and then fails on a real request,
because Spring resolves plenty of things reflectively at startup. So it was
checked against the real local Db2:

| | |
|---|---|
| `./mvnw clean verify -DskipITs` | BUILD SUCCESS, full test suite |
| `Db2 connectivity check` | OK |
| Registered tools (`SAFE_QUERY`) | 10 |
| `tools/list` | 10 tools |
| `listSchemas` | returned real catalog data |
| `executeQuery` outside the allowlist | correctly rejected `SCOPE_DENIED` |
| Span and metric export records | 99 |
| `ClassNotFoundException` / `NoClassDefFoundError` | **0** |

The 99 export records matter specifically for `aopalliance`: they prove the
Micrometer tracing path still works without it, rather than merely that the
application started.

**Removing the standalone jar is not proof the code is gone.** `aopalliance`
was excluded because its *own artifact* is not distributable on verified
terms. Spring Framework ships its own copies of those four interfaces inside
`spring-aop`, which remains on the classpath, so third-party-derived
interface code may well still be present — under Spring's Apache-2.0 terms
rather than an unverified public-domain dedication, which is the point. This
ADR claims the standalone artifact is gone, not that every byte of
AOP-Alliance-derived interface code vanished.

**Dependency upgrades can reintroduce these.** A future Spring AI version
could restructure `spring-ai-model` so the template module arrives by
another path, and the exclusion would silently stop matching. The licence
profile is therefore worth re-reading when Spring AI is upgraded. This is
not currently enforced by a check — stated plainly rather than implied,
because an unenforced consequence is a real one.

**A reader comparing the SBOM to earlier evidence will see 111, not 115.**
Four components are gone and the Phase 1 licensing evidence predates that.

## Alternatives considered

**Distribute no third-party libraries at all — ship only our own code.**
Rejected, after being taken seriously, because the costs are real and the
legal benefit is close to zero.

The mechanism exists: the jar already launches through `PropertiesLauncher`
(ADR 0048), so a `lib/` directory supplied via `-Dloader.path` would work
the same way the external driver does. What it would cost:

- **Every operator assembles 102 jars.** An MCP client launches this server
  as a subprocess with a fixed command; "first obtain these 102 artifacts"
  is a serious adoption barrier for a project whose value is being read and
  tried.
- **It does not actually achieve "only our own code".** An executable jar
  needs Spring Boot's own loader classes inside it to be executable at all.
  Reaching literally zero third-party bytes means giving up the executable
  jar.
- **The legal gain is nil for the 96 permissive components.** Apache-2.0,
  MIT, MIT-0 and BSD-3-Clause grant redistribution explicitly and
  unconditionally apart from preserving notices — which is done, generated
  from the artifacts, and checked in CI. There is no unread permission to
  worry about.
- **It would not have found these three.** Blanket removal and targeted
  removal both drop ST4; only the targeted analysis establishes *why* it
  mattered, which is the part that transfers to the next dependency change.

The precise version of the instinct — *do not ship what you cannot verify* —
is strictly better than the blunt version, and it is what this ADR adopts.

**Keep ST4 and comply with the advertising clause.** Rejected: it means
every future piece of marketing material carries an acknowledgement for a
dependency pulled in by an unused feature. The obligation is cheap to avoid
and expensive to remember.

**Keep them and record an assumption that `antlr-runtime` is BSD-3-Clause.**
Rejected. That is precisely the move ADR 0048 exists to stop — asserting a
licensing fact that has not been verified, which is how the IBM driver came
to be bundled in the first place.
