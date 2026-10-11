# Live-DB2 test prerequisites (local Docker setup)

Everything in this repository's automated suite is Tier 1 (H2) or
honestly-labeled Tier 2, never verified against a live DB2 (ADR 0009) -
this environment has no Docker daemon and no raw-TCP network access. That
constraint is specific to *this development environment*, not to DB2
itself: a real DB2 instance reachable from your own machine removes it
entirely. This doc is the reusable runbook for getting there, independent
of any one person's exact paths or container names.

It's a prerequisite step, not the test itself - once a DB2 instance is up
and reachable, the actual verification is
[`guided-scenarios.md`'s "Live-DB2 verification checklist"](guided-scenarios.md#live-db2-verification-checklist),
which names the exact tool call and exact expected result for each of
this project's still-open Tier 2 claims (CR-003/CR-004, ADR 0022).

## 1. A running DB2 instance reachable from your machine

Any DB2 LUW instance works, as long as it's reachable from wherever
you'll run the server and a client can execute the CLP (`db2 -td@ ...`)
against it. The common local path is Docker Desktop (with WSL2 on
Windows) running IBM's `db2` container image via a `docker-compose.yml`
you control - something like:

```yaml
services:
  db2:
    image: icr.io/db2_community/db2
    privileged: true
    ports:
      - "50000:50000"
    environment:
      - LICENSE=accept
      - DB2INSTANCE=db2inst1
      - DB2INST1_PASSWORD=${DB2INST1_PASSWORD}
      - DBNAME=testdb
    volumes:
      - db2-data:/database
volumes:
  db2-data:
```

Keep the password in an untracked `.env` file, never in the compose file
or in git - the same "credentials never committed" discipline this
project's own `application.yml` follows (`${DB2_USER}`/`${DB2_PASSWORD}`
placeholders, ADR 0015's effective-config report masking).

Bring it up:

```
docker compose --env-file <path-to-your-.env> -f <path-to-compose-file> up -d
```

Confirm it's actually healthy before moving on - don't assume:

```
docker ps --filter name=<container-name>
```

## 2. Load the schema and seed data

A Dockerized DB2's CLP lives *inside* the container, not on the Docker
host - `guided-scenarios.md`'s own Setup section assumes a `db2` binary
directly on the machine running the command, which isn't true here. Copy
both files in and run them through the container's own CLP, schema
first:

```
docker cp docs/demo/itsm-schema.sql <container-name>:/tmp/itsm-schema.sql
docker cp docs/demo/itsm-test-data.sql <container-name>:/tmp/itsm-test-data.sql
docker exec -it <container-name> su - <db2-instance-user> -c \
    "db2 connect to <database> && \
     db2 -td@ -f /tmp/itsm-schema.sql && \
     db2 -td@ -f /tmp/itsm-test-data.sql"
```

`itsm-schema.sql` is structure only (tables, constraints, procedures,
role/grants); `itsm-test-data.sql` is the fictional seed data - kept
separate so the two concerns don't mix, and must run in that order since
the data has foreign keys into the schema. `-td@` (not the default `;`)
is required for the schema file because `STP_FIND_TICKETS`'s body
contains `;` internally - harmless on the data file too, repeated here
because it's easy to miss when adapting the command for `docker exec`.

## 2b. Provision EXPLAIN tables (only needed for the explainQuery checklist item, ADR 0033)

`explainQuery` is `ADVANCED_QUERY`-only and reads DB2's own
`EXPLAIN_STATEMENT`/`EXPLAIN_OPERATOR` catalog tables, which are **not**
part of the ITSM demo schema and are not present in a fresh DB2 database
by default - IBM ships them as a separate DDL script, traditionally at
`sqllib/misc/EXPLAIN.DDL` inside the DB2 install (container or host).
Skip this step entirely if you're only running scenarios 1-6 or the
`getCapabilities`/`executeQuery` checklist items.

```
docker exec -it <container-name> su - <db2-instance-user> -c \
    "db2 connect to <database> && \
     db2 -tf sqllib/misc/EXPLAIN.DDL"
```

This is the one part of ADR 0033 genuinely unverified from this
environment - confirm when running the checklist: whether that path
exists as-is in this image's DB2 version, what schema the tables land
in if run without an explicit `SET SCHEMA` first, and whether the
least-privilege role from ADR 0022 needs an additional grant to read
them (it already has `SELECT` on ordinary ITSM tables, but the EXPLAIN
tables are a separate object family). Note whatever you find as a
correction to ADR 0033's Verification section, the same way the
CR-003/CR-004 live-DB2 session corrected `itsm-schema.sql`'s own bugs
in place rather than filing them as a separate followup.

## 3. Point the server at it

```
DB2_HOST=<host, e.g. localhost> DB2_PORT=<port, e.g. 50000> \
DB2_DATABASE=<database> DB2_USER=<db2-instance-user> DB2_PASSWORD=<password> \
DB2MCP_CAPABILITY_PROFILE=SAFE_QUERY DB2MCP_ALLOWEDSCHEMAS=ITSM \
java -jar target/db2-mcp-server-*.jar
```

Build the jar first (`mvn -DskipTests package`) if it doesn't already
exist. `SAFE_QUERY` is required for guided scenario 5 (`executeQuery`);
`METADATA_ONLY` is enough for scenarios 1-4 and the verification
checklist's `getCapabilities` item.

## 4. Run the checklist

Move on to
[`guided-scenarios.md`'s "Live-DB2 verification checklist"](guided-scenarios.md#live-db2-verification-checklist).
Each item is a checkbox naming an exact tool call and an exact expected
result - work through it literally, and if any actual result differs
from what's written, that's a real bug to fix, not a doc to quietly
reword (the same standard every other Tier 2 → Tier 1 promotion in this
project has held to).
