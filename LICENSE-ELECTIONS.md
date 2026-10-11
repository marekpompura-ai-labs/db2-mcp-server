# Third-party licence elections

These elections apply to the third-party binaries distributed with release
1.0.1. They are separate from the licence for original project material.

| Component | Version | Elected licence |
|---|---|---|
| `com.github.jsqlparser:jsqlparser` | 5.4 | Apache-2.0 |
| `jakarta.annotation:jakarta.annotation-api` | 3.0.0 | EPL-2.0 |
| `ch.qos.logback:logback-classic` | 1.5.38 | EPL-2.0 |
| `ch.qos.logback:logback-core` | 1.5.38 | EPL-2.0 |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | BSD-2-Clause |

The machine-readable elections in
[`third-party-licenses/MANIFEST.json`](third-party-licenses/MANIFEST.json)
must match this table.

For the EPL components, [`SOURCE-AVAILABILITY.md`](SOURCE-AVAILABILITY.md)
identifies the matching source archives and explains how recipients obtain
them under EPL-2.0. This applies to these unmodified binary components too.
Their own licence and source rights remain intact; PolyForm Strict applies
only to original project material.

For Apache-2.0, retain the licence and applicable upstream notices. For
BSD-2-Clause, retain the copyright notice, conditions and disclaimer.
HdrHistogram is not exempt from those obligations: its embedded licence is
reproduced in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

The MCP Java SDK, jtokkit and ANTLR 4 runtime are not licence elections.
Their required MIT or BSD material is provided in `third-party-licenses/`,
with version-pinned provenance and hashes in the manifest.

The binary distribution includes these documents and licence texts under
`META-INF/legal/`. The release check verifies those embedded bytes against
the repository copies. The check verifies recorded material and does not
replace legal judgment about obligations or enforceability.
