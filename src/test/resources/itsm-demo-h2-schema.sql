-- H2 (MODE=DB2) translation of docs/demo/itsm-schema.sql +
-- docs/demo/itsm-test-data.sql (structure and fictional seed data, kept
-- separate on the DB2 side but combined back into one file here since H2
-- is test-only and doesn't need that boundary), used only to verify the
-- Tier 1 guided scenarios (4, 5, 6 - master brief section 17.1) actually
-- produce the results docs/demo/guided-scenarios.md claims for them (ADR
-- 0009). Not used by the rest of the automated suite, which has its own
-- smaller fixture (itsm-h2-schema.sql) - kept separate so demo data and
-- general test data don't drift into each other by accident.
--
-- Omits the STP_FIND_TICKETS stored procedure: H2 has no equivalent of
-- DB2's SQL PL, and scenario 3 (which exercises it) is Tier 2 regardless -
-- unverifiable in this environment no matter what H2 can approximate.

CREATE SCHEMA ITSM;

CREATE TABLE ITSM.REF_DATA (
    CATEGORY    VARCHAR(30)  NOT NULL,
    CODE        VARCHAR(10)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    PRIMARY KEY (CATEGORY, CODE)
);

CREATE TABLE ITSM.SERVICE_CATALOG (
    SERVICE_ID   INTEGER      NOT NULL PRIMARY KEY,
    SERVICE_NAME VARCHAR(100) NOT NULL,
    DESCRIPTION  VARCHAR(250)
);

CREATE TABLE ITSM.CUSTOMER (
    CUSTOMER_ID INTEGER     NOT NULL PRIMARY KEY,
    FIRST_NAME  VARCHAR(50) NOT NULL,
    LAST_NAME   VARCHAR(50) NOT NULL
);

CREATE TABLE ITSM.TICKET (
    TICKET_ID   INTEGER      NOT NULL PRIMARY KEY,
    TITLE       VARCHAR(200) NOT NULL,
    CUSTOMER_ID INTEGER      NOT NULL,
    SERVICE_ID  INTEGER      NOT NULL,
    PRIORITY    CHAR(1)      NOT NULL,
    STATUS      CHAR(2)      NOT NULL,
    CREATED_AT  TIMESTAMP    NOT NULL
);

CREATE TABLE ITSM.TICKET_COMMENT (
    COMMENT_ID   INTEGER       NOT NULL PRIMARY KEY,
    TICKET_ID    INTEGER       NOT NULL,
    AUTHOR       VARCHAR(50)   NOT NULL,
    COMMENT_TEXT VARCHAR(1000) NOT NULL,
    CREATED_AT   TIMESTAMP     NOT NULL
);

INSERT INTO ITSM.REF_DATA (CATEGORY, CODE, DESCRIPTION) VALUES
    ('PRIORITY', 'H',  'High'),
    ('PRIORITY', 'N',  'Normal'),
    ('PRIORITY', 'L',  'Low'),
    ('STATUS',   'OP', 'Open'),
    ('STATUS',   'IP', 'In Progress'),
    ('STATUS',   'CL', 'Closed');

INSERT INTO ITSM.SERVICE_CATALOG (SERVICE_ID, SERVICE_NAME, DESCRIPTION) VALUES
    (1, 'VPN Access', 'Remote network access provisioning and troubleshooting'),
    (2, 'Password Reset', 'Self-service and assisted credential resets'),
    (3, 'Laptop Provisioning', 'New hardware requests and configuration');

INSERT INTO ITSM.CUSTOMER (CUSTOMER_ID, FIRST_NAME, LAST_NAME) VALUES
    (1, 'Alex', 'Nowak'),
    (2, 'Priya', 'Iyer'),
    (3, 'Sam', 'Okafor');

INSERT INTO ITSM.TICKET (TICKET_ID, TITLE, CUSTOMER_ID, SERVICE_ID, PRIORITY, STATUS, CREATED_AT) VALUES
    (101, 'Cannot connect to VPN from home network', 1, 1, 'H', 'OP', '2026-08-01 09:15:00'),
    (102, 'Password reset request', 2, 2, 'L', 'CL', '2026-08-02 11:40:00'),
    (103, 'New laptop for onboarding', 3, 3, 'N', 'IP', '2026-08-03 14:05:00'),
    (104, 'VPN drops intermittently during video calls', 2, 1, 'N', 'OP', '2026-08-05 08:30:00');

INSERT INTO ITSM.TICKET_COMMENT (COMMENT_ID, TICKET_ID, AUTHOR, COMMENT_TEXT, CREATED_AT) VALUES
    (1001, 101, 'helpdesk.alex', 'Confirmed VPN client version is outdated, sending update link.', '2026-08-01 09:40:00'),
    (1002, 102, 'helpdesk.priya', 'Password reset completed via self-service portal.', '2026-08-02 11:45:00'),
    (1003, 103, 'helpdesk.sam', 'Hardware order placed, ETA 3 business days.', '2026-08-03 14:20:00');
