# Licence history

This file records which licence governed which release, so that anyone who
received an earlier release can see exactly what they hold.

| Release | Licence for original project material |
|---|---|
| `1.0.0` (2026-09-24) | Apache License 2.0 — preserved at [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt) |
| `1.0.1` onward | [PolyForm Strict License 1.0.0](LICENSE), plus the supplementary [Enterprise Evaluation Permission](ENTERPRISE-EVALUATION.md) |

## Rights granted by 1.0.0 are not withdrawn

Release `1.0.0` was published under the Apache License 2.0. A licence change
applies to the releases made under the new terms; it does not reach back and
revoke what was already granted.

Anyone who obtained release `1.0.0` holds Apache-2.0 rights **in that
material** — including the rights to use, modify, and redistribute it
commercially — and those rights are perpetual and irrevocable on
Apache-2.0's own terms. Nothing in the current `LICENSE` or in
`ENTERPRISE-EVALUATION.md` is intended to diminish them, and neither
document purports to.

**Being precise about what a version number does and does not do.** `1.0.1`
changes the terms on which *new* copies are offered. It does not, and cannot,
make the code that was released under Apache-2.0 exclusive again. The `1.0.1`
patch contains **no change to any Java implementation file** — it is
licensing, packaging and documentation only — so the application code a
1.0.0 recipient holds is, as a matter of fact, the same code. Their
Apache-2.0 rights in it continue. Strict governs this project's new offer,
not their existing copy.

What changed is the licence offered for releases from `1.0.1` onward. Those
releases are offered under PolyForm Strict 1.0.0, which permits use for any
noncommercial purpose but does not grant rights to modify or redistribute,
plus the supplementary evaluation permission that adds rights for commercial
evaluation.

## Why the change

Recorded in `LICENSING-DECISION.md` (kept privately, not published with the
source). In short: the Apache grant gave away the commercial rights this
project is intended to carry, while the engineering substance of the project
is still meant to be readable, runnable and evaluable by anyone — including
commercially. Strict plus an explicit evaluation permission expresses that;
Apache-2.0 did not.

## Third-party components are unaffected

Every third-party dependency remains under its own licence throughout. The
licence change concerns original project material only. See
[`NOTICE`](NOTICE) and [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
