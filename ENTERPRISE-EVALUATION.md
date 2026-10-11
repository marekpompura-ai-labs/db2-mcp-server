# DB2 MCP Server — Enterprise Evaluation Permission

Version 1.1. Applies to releases deliberately distributed with this document
by the copyright holder.

Copyright © 2026 Marek Pompura.

## 1. Scope and relationship to other licences

This supplementary permission applies only to original DB2 MCP Server
material in releases with which the copyright holder includes this document.

The copyright holder grants each individual or organization accepting these
terms a non-exclusive, worldwide, royalty-free copyright licence to obtain,
reproduce, inspect, compile and run the unmodified software, and to provide
copies solely to the employees and contractors authorized by section 2,
exclusively for the evaluation described in sections 2 and 3 and subject to
sections 1–7. This supplementary licence is independent of, and additional
to, the PolyForm Strict License 1.0.0. It does not amend that licence or
restrict rights independently available under it, earlier licences,
applicable law or hosting-platform terms. It grants no right to modify the
implementation, create derivative versions, sublicense, or distribute
outside the authorized evaluation participants.

Third-party components remain governed by their own licences.

## 2. Permitted evaluation

An individual or organization, including a commercial organization, may
obtain, inspect, copy internally, compile and run the unmodified software
solely to assess its suitability, compatibility, security, performance or
potential acquisition, integration or collaboration.

Evaluation has no fixed time limit. Each use must remain within this
permitted purpose; calling an operational deployment an "evaluation" does
not make it permitted.

Employees may participate on behalf of the evaluating organization.
Contractors may participate under its direction solely in its evaluation,
subject to these terms, without independent reuse or onward distribution.
The organization must ensure that participants follow these terms.

## 3. Non-production boundary

Evaluation must take place in an isolated environment whose operation and
outputs are not relied upon to operate, support or deliver a live business
system or service.

Permitted examples include:

* Synthetic or demonstration datasets.
* A separately controlled copy of production data, where the evaluator is
  authorized to use that data.
* A staging or pre-production environment used solely for evaluation,
  isolated from live operational workflows.
* Evaluation by a commercial engineering team, including assessment of a
  possible future commercial deployment.

This permission does not authorize:

* Connecting the software to a live production database, even with
  read-only credentials.
* Using it for actual incident response, customer support, operational
  monitoring, recurring business reporting or decisions affecting live
  operations.
* Using staging, replicas or copied data to perform those operational
  activities.
* Delivering paid client work, providing a hosted service, or incorporating
  it into an operational product or service.

Evaluation findings may inform a decision to acquire or deploy the software
under a separate agreement.

## 4. Configuration, modifications and distribution

The evaluator may change external configuration, environment variables,
connection settings and evaluation datasets, and may connect independently
authored clients through the software's published interfaces.

This does not grant permission to modify the implementation, create
derivative versions, redistribute the software outside the authorized
evaluation participants, sublicense it, or make it available to customers or
the public.

Internal copies and builds must retain applicable licence and attribution
notices. No rights are claimed over independently authored client code,
evaluator data or evaluation reports.

## 5. IBM software and data rights

IBM JCC and platform activation files are not supplied under this
permission. The evaluator must obtain and use any required IBM software
under applicable IBM terms and must have authorization to access and process
the evaluation data.

## 6. Duration and termination

For a release received with this permission, the grant has no scheduled
expiry and is not withdrawn merely because later releases use different
terms.

If the evaluator breaches these terms, the copyright holder may give written
notice identifying the breach. Rights under this supplementary permission
terminate if the evaluator does not cease the breach and remedy it where
reasonably possible within 32 days of receiving notice.

On termination, use relying on this permission must stop. Copies may be
retained where independently licensed or legally required, subject to those
separate rights and requirements.

## 7. Other use and disclaimer

Use outside this permission requires a separate agreement unless
independently authorized.

The software is provided as is, without warranties or an obligation to
provide support, to the extent permitted by applicable law. Mandatory legal
rights remain unaffected.

Commercial licensing and collaboration:
[marek.pompura.it@gmail.com](mailto:marek.pompura.it@gmail.com)

---

## Annex — technical notes for the evaluator

*This annex is explanatory. It is not part of the permission granted above
and does not change it.*

Two behaviours of the software bear on the boundary drawn in section 3, and
are stated here so that an evaluation environment can be set up correctly.

**The `explainQuery` tool writes to the database.** The software is
read-only with one documented exception. Under the `ADVANCED_QUERY`
capability profile, `explainQuery` issues `EXPLAIN PLAN ... FOR <statement>`,
which does not run the explained statement but does write plan metadata into
Db2's own `EXPLAIN_STATEMENT` and `EXPLAIN_OPERATOR` tables, which are then
read back.

This matters **only if you evaluate the `ADVANCED_QUERY` profile.** The
`METADATA_ONLY` and `SAFE_QUERY` profiles never expose `explainQuery`, so an
evaluator using them needs no write privilege of any kind and a strictly
read-only account is fine. To exercise `ADVANCED_QUERY`, the evaluation
database needs the Db2 EXPLAIN tables provisioned and must permit writes to
them - so use a writable isolated test database for that profile rather than
a genuinely read-only replica, which cannot run the tool at all. Because the
write lands in whichever database the software is pointed at, a replica or
copied dataset used for that profile will receive those rows. See [ADR 0033](docs/adr/0033-explain-plan-acquisition.md)
and `docs/security/threat-model.md`.

**Building the project resolves an IBM driver from a public repository.**
Section 5 means that **no IBM rights are granted by this permission** - it
does not mean that no IBM bytes ever cross your network. No IBM driver is
distributed in any artifact of this project. The build does, however, declare
the driver as a `test`-scope dependency, so running the test suite resolves
it from a public Maven repository into the evaluator's own local repository.
That download, and any use of the driver, are governed by IBM's terms and not
by this permission. Running the packaged application needs no such download
at all: the operator supplies the driver externally. An evaluator who
prefers not to resolve it that way can supply the driver externally — see
[`docs/operations/external-jdbc-driver.md`](docs/operations/external-jdbc-driver.md).
