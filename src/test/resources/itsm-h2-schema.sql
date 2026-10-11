-- Minimal subset of the ITSM demo schema (from prompt.create.db2.mcp.test.schema),
-- recreated in H2 for Tier 1 integration coverage without a live Db2 instance (ADR 0009).
-- This is a test-only H2 approximation, not a substitute for the real DB2 DDL.

CREATE SCHEMA ITSM;

CREATE TABLE ITSM.REF_DATA (
    CATEGORY    VARCHAR(30)  NOT NULL,
    CODE        VARCHAR(10)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    PRIMARY KEY (CATEGORY, CODE)
);
INSERT INTO ITSM.REF_DATA VALUES ('PRIORITY', 'H', 'High');
INSERT INTO ITSM.REF_DATA VALUES ('PRIORITY', 'L', 'Low');
INSERT INTO ITSM.REF_DATA VALUES ('PRIORITY', 'N', 'Normal');
INSERT INTO ITSM.REF_DATA VALUES ('STATUS', 'OP', 'Open');
INSERT INTO ITSM.REF_DATA VALUES ('STATUS', 'CL', 'Closed');

CREATE TABLE ITSM.CUSTOMER (
    CUSTOMER_ID INTEGER      NOT NULL PRIMARY KEY,
    FIRST_NAME  VARCHAR(50)  NOT NULL,
    LAST_NAME   VARCHAR(50)  NOT NULL
);
INSERT INTO ITSM.CUSTOMER VALUES (1, 'Anna', 'Schmidt');
INSERT INTO ITSM.CUSTOMER VALUES (2, 'Marco', 'Rossi');

CREATE TABLE ITSM.TICKET (
    TICKET_ID   INTEGER      NOT NULL PRIMARY KEY,
    TITLE       VARCHAR(200) NOT NULL,
    CUSTOMER_ID INTEGER      NOT NULL,
    PRIORITY    CHAR(1)      NOT NULL,
    STATUS      CHAR(2)      NOT NULL
);
INSERT INTO ITSM.TICKET VALUES (1, 'Cannot connect to VPN', 1, 'H', 'OP');
INSERT INTO ITSM.TICKET VALUES (2, 'Password reset request', 2, 'L', 'CL');

-- Used only to verify the 1000-row cap/truncation behavior (ADR 0007) against
-- a real ResultSet, not a mocked one.
CREATE TABLE ITSM.BULK_ROWS (N INTEGER NOT NULL);
INSERT INTO ITSM.BULK_ROWS (N) SELECT X FROM SYSTEM_RANGE(1, 1001);

-- A schema deliberately outside the allowlist used in tests, to verify
-- SchemaAllowlistValidator rejects it even though the table genuinely exists
-- and is genuinely queryable - not just a fictitious name (ADR 0008).
CREATE SCHEMA OTHER_SCHEMA;
CREATE TABLE OTHER_SCHEMA.SECRET (ID INTEGER NOT NULL PRIMARY KEY);
INSERT INTO OTHER_SCHEMA.SECRET VALUES (1);
