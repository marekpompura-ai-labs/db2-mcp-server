-- ITSM demo schema (master brief section 17) - structure only: tables,
-- constraints, procedures, and the least-privilege role/grants. No data.
-- Seed data lives separately in itsm-test-data.sql, run after this file -
-- kept apart so the structural/compliance boundary ("no real people,
-- employers, customers, or internal naming" - the brief's explicit
-- requirement for the public demo) is visible at a glance instead of
-- interleaved with 180 lines of DDL.
--
-- Run this against a real DB2 (LUW) instance as a user with schema-creation
-- authority. It is deliberately plain, portable SQL PL - no vendor-specific
-- tooling beyond DB2 itself is required.
--
-- This is the full demo schema. It is a superset of the minimal fixture used
-- by the automated test suite (src/test/resources/itsm-h2-schema.sql, ADR
-- 0009) - that file exists purely to exercise the guard chain against H2 in
-- this environment and is not meant to double as demo data.
--
-- Also doubles as this project's live-DB2 verification vehicle for CR-003
-- and CR-004 (ADR 0022): STP_FIND_TICKETS is deliberately overloaded, and
-- SERVICE_CATALOG_NOTE deliberately references a unique constraint rather
-- than a primary key. See docs/demo/guided-scenarios.md's "Live-DB2
-- verification checklist" for what to run once this schema is loaded.
--
-- Statement terminator is "@" throughout (not the default ";"), required by
-- the two CREATE PROCEDURE bodies below whose SQL PL contains internal ";"
-- statement separators - run with `db2 -td@ -f itsm-schema.sql`. The ";"
-- characters inside the CREATE PROCEDURE bodies are ordinary SQL PL
-- statement separators, not CLP terminators, and are unaffected.

CREATE SCHEMA ITSM@

-- Reference data: category/code/description lookups, exercised by the
-- lookupRefData tool (guided scenario 4, master brief section 17.1).
CREATE TABLE ITSM.REF_DATA (
    CATEGORY    VARCHAR(30)  NOT NULL,
    CODE        VARCHAR(10)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    CONSTRAINT PK_REF_DATA PRIMARY KEY (CATEGORY, CODE)
)@

CREATE TABLE ITSM.SERVICE_CATALOG (
    SERVICE_ID   INTEGER      NOT NULL,
    SERVICE_NAME VARCHAR(100) NOT NULL,
    DESCRIPTION  VARCHAR(250),
    CONSTRAINT PK_SERVICE_CATALOG PRIMARY KEY (SERVICE_ID),
    -- A second, independent unique key - deliberately not just the primary
    -- key restated. SERVICE_CATALOG_NOTE (below) references this one, not
    -- SERVICE_ID, giving getTableDependencies a live case where the
    -- referenced key isn't the parent's primary key (CR-004/ADR 0022:
    -- SYSCAT.REFERENCES.REFKEYNAME must be resolved, not assumed to be the
    -- PK). Never verified against real DB2 catalog behavior until this
    -- schema is run there.
    CONSTRAINT UQ_SERVICE_CATALOG_NAME UNIQUE (SERVICE_NAME)
)@

CREATE TABLE ITSM.CUSTOMER (
    CUSTOMER_ID INTEGER     NOT NULL,
    FIRST_NAME  VARCHAR(50) NOT NULL,
    LAST_NAME   VARCHAR(50) NOT NULL,
    CONSTRAINT PK_CUSTOMER PRIMARY KEY (CUSTOMER_ID)
)@

-- Two outbound foreign keys (CUSTOMER, SERVICE_CATALOG) and one inbound
-- (TICKET_COMMENT, below) give getTableDependencies (guided scenario 2)
-- something real to report in both directions.
CREATE TABLE ITSM.TICKET (
    TICKET_ID   INTEGER      NOT NULL,
    TITLE       VARCHAR(200) NOT NULL,
    CUSTOMER_ID INTEGER      NOT NULL,
    SERVICE_ID  INTEGER      NOT NULL,
    PRIORITY    CHAR(1)      NOT NULL,
    STATUS      CHAR(2)      NOT NULL,
    CREATED_AT  TIMESTAMP    NOT NULL DEFAULT CURRENT TIMESTAMP,
    CONSTRAINT PK_TICKET PRIMARY KEY (TICKET_ID),
    CONSTRAINT FK_TICKET_CUSTOMER FOREIGN KEY (CUSTOMER_ID) REFERENCES ITSM.CUSTOMER (CUSTOMER_ID),
    CONSTRAINT FK_TICKET_SERVICE FOREIGN KEY (SERVICE_ID) REFERENCES ITSM.SERVICE_CATALOG (SERVICE_ID)
)@

CREATE TABLE ITSM.TICKET_COMMENT (
    COMMENT_ID   INTEGER       NOT NULL,
    TICKET_ID    INTEGER       NOT NULL,
    AUTHOR       VARCHAR(50)   NOT NULL,
    COMMENT_TEXT VARCHAR(1000) NOT NULL,
    CREATED_AT   TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP,
    CONSTRAINT PK_TICKET_COMMENT PRIMARY KEY (COMMENT_ID),
    CONSTRAINT FK_COMMENT_TICKET FOREIGN KEY (TICKET_ID) REFERENCES ITSM.TICKET (TICKET_ID)
)@

-- References SERVICE_CATALOG.SERVICE_NAME - the unique constraint added
-- above, not the primary key SERVICE_ID. See that constraint's comment
-- (CR-004/ADR 0022).
CREATE TABLE ITSM.SERVICE_CATALOG_NOTE (
    NOTE_ID      INTEGER      NOT NULL,
    SERVICE_NAME VARCHAR(100) NOT NULL,
    NOTE_TEXT    VARCHAR(500) NOT NULL,
    CONSTRAINT PK_SERVICE_CATALOG_NOTE PRIMARY KEY (NOTE_ID),
    CONSTRAINT FK_NOTE_SERVICE_NAME FOREIGN KEY (SERVICE_NAME) REFERENCES ITSM.SERVICE_CATALOG (SERVICE_NAME)
)@

-- Stored procedure, guided scenario 3 (listProcedures / describeProcedure /
-- getProcedureSource). A native SQL procedure, not external/Java, so its
-- source text is retrievable from SYSCAT.PROCEDURES.TEXT - getProcedureSource
-- is documented as SQL-procedures-only for exactly this reason.
--
-- Deliberately overloaded: two procedures share this schema and name with
-- different parameter signatures, each given an explicit SPECIFIC name for
-- a deterministic demo (DB2 would otherwise generate one). This is
-- CR-003/ADR 0022's exact case - describeProcedure/getProcedureSource must
-- key on SPECIFICNAME (from listProcedures), not PROCEDURENAME, to select
-- one overload unambiguously. Never verified against real DB2 catalog
-- behavior until this schema is run there.
CREATE PROCEDURE ITSM.STP_FIND_TICKETS (
    IN P_STATUS   CHAR(2),
    IN P_PRIORITY CHAR(1)
)
SPECIFIC STP_FIND_TICKETS_BY_STATUS
LANGUAGE SQL
READS SQL DATA
DYNAMIC RESULT SETS 1
BEGIN
    DECLARE C1 CURSOR WITH RETURN FOR
        SELECT TICKET_ID, TITLE, CUSTOMER_ID, SERVICE_ID, PRIORITY, STATUS, CREATED_AT
        FROM ITSM.TICKET
        WHERE STATUS = P_STATUS
          AND PRIORITY = P_PRIORITY
        ORDER BY CREATED_AT;
    OPEN C1;
END
@

CREATE PROCEDURE ITSM.STP_FIND_TICKETS (
    IN P_CUSTOMER_ID INTEGER
)
SPECIFIC STP_FIND_TICKETS_BY_CUSTOMER
LANGUAGE SQL
READS SQL DATA
DYNAMIC RESULT SETS 1
BEGIN
    DECLARE C1 CURSOR WITH RETURN FOR
        SELECT TICKET_ID, TITLE, CUSTOMER_ID, SERVICE_ID, PRIORITY, STATUS, CREATED_AT
        FROM ITSM.TICKET
        WHERE CUSTOMER_ID = P_CUSTOMER_ID
        ORDER BY CREATED_AT;
    OPEN C1;
END
@

-- ---------------------------------------------------------------------------
-- Least-privilege read-only principal (ADR 0002 layer 1: DB2 grants).
-- DB2 LUW authenticates users externally (OS/LDAP), so this grants a ROLE
-- rather than creating a login - attach the role to whichever authenticated
-- user DB2MCP_DB_USER resolves to for this demo.
-- ---------------------------------------------------------------------------

CREATE ROLE ITSM_MCP_READONLY@

-- No schema-level grant needed here: unlike Oracle/Postgres, DB2 LUW has
-- no USAGE privilege on SCHEMA (valid schema privileges are CREATEIN/
-- ALTERIN/DROPIN, all DDL rights this read-only role has no business
-- holding) - the object-level SELECT/EXECUTE grants below are sufficient
-- on their own to read existing objects. A prior "GRANT USAGE ON SCHEMA
-- ITSM TO ROLE ITSM_MCP_READONLY" line here failed with SQL0557N against
-- a real DB2 instance (caught during ADR 0022's live-DB2 verification)
-- for exactly this reason and was removed, not replaced.
GRANT SELECT ON ITSM.REF_DATA TO ROLE ITSM_MCP_READONLY@
GRANT SELECT ON ITSM.SERVICE_CATALOG TO ROLE ITSM_MCP_READONLY@
GRANT SELECT ON ITSM.CUSTOMER TO ROLE ITSM_MCP_READONLY@
GRANT SELECT ON ITSM.TICKET TO ROLE ITSM_MCP_READONLY@
GRANT SELECT ON ITSM.TICKET_COMMENT TO ROLE ITSM_MCP_READONLY@
GRANT SELECT ON ITSM.SERVICE_CATALOG_NOTE TO ROLE ITSM_MCP_READONLY@
-- Overloaded procedures can't be granted by unqualified name (ambiguous) -
-- grant each SPECIFIC instance individually.
GRANT EXECUTE ON SPECIFIC PROCEDURE ITSM.STP_FIND_TICKETS_BY_STATUS TO ROLE ITSM_MCP_READONLY@
GRANT EXECUTE ON SPECIFIC PROCEDURE ITSM.STP_FIND_TICKETS_BY_CUSTOMER TO ROLE ITSM_MCP_READONLY@

-- The metadata tools (listSchemas, listTables, describeTable,
-- getTableDependencies, listProcedures, describeProcedure) read SYSCAT
-- views, which most DB2 LUW instances grant SELECT on to PUBLIC by default.
-- If this instance has that restricted, also grant explicitly:
-- GRANT SELECT ON SYSCAT.SCHEMATA, SYSCAT.TABLES, SYSCAT.COLUMNS,
--   SYSCAT.KEYCOLUSE, SYSCAT.TABCONST, SYSCAT.REFERENCES, SYSCAT.PROCEDURES,
--   SYSCAT.PROCPARMS TO ROLE ITSM_MCP_READONLY;

-- GRANT ROLE ITSM_MCP_READONLY TO USER <db2_user>;

-- Next: itsm-test-data.sql (fictional seed data), then the guided scenarios
-- (docs/demo/guided-scenarios.md) or the live-DB2 verification checklist.
