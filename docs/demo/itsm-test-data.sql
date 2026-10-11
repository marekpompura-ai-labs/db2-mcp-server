-- ITSM demo seed data (master brief section 17.1) - fictional IT-support
-- data only. No real people, employers, customers, or internal naming, per
-- the brief's explicit requirement for the public demo: every name, ticket,
-- and comment below was invented for this project, not sourced from any
-- real system.
--
-- Run itsm-schema.sql first - this file only inserts into tables that
-- script creates, and CUSTOMER/SERVICE_CATALOG/TICKET/TICKET_COMMENT have
-- foreign keys to each other, so load order (below) matters.
--
-- Kept separate from itsm-schema.sql so the structural/compliance boundary
-- is visible at a glance: everything here is data, everything there is
-- schema (tables, constraints, procedures, grants) - no fictional content
-- to review mixed into DDL, and no DDL to review mixed into data.
--
-- Statement terminator is "@" (matching itsm-schema.sql's -td@ invocation,
-- required there by the CREATE PROCEDURE bodies) - run with
-- `db2 -td@ -f itsm-test-data.sql`.

INSERT INTO ITSM.REF_DATA (CATEGORY, CODE, DESCRIPTION) VALUES
    ('PRIORITY', 'H',  'High'),
    ('PRIORITY', 'N',  'Normal'),
    ('PRIORITY', 'L',  'Low'),
    ('STATUS',   'OP', 'Open'),
    ('STATUS',   'IP', 'In Progress'),
    ('STATUS',   'CL', 'Closed')@

INSERT INTO ITSM.SERVICE_CATALOG (SERVICE_ID, SERVICE_NAME, DESCRIPTION) VALUES
    (1, 'VPN Access', 'Remote network access provisioning and troubleshooting'),
    (2, 'Password Reset', 'Self-service and assisted credential resets'),
    (3, 'Laptop Provisioning', 'New hardware requests and configuration')@

INSERT INTO ITSM.CUSTOMER (CUSTOMER_ID, FIRST_NAME, LAST_NAME) VALUES
    (1, 'Alex', 'Nowak'),
    (2, 'Priya', 'Iyer'),
    (3, 'Sam', 'Okafor')@

INSERT INTO ITSM.TICKET (TICKET_ID, TITLE, CUSTOMER_ID, SERVICE_ID, PRIORITY, STATUS, CREATED_AT) VALUES
    (101, 'Cannot connect to VPN from home network', 1, 1, 'H', 'OP', '2026-08-01-09.15.00'),
    (102, 'Password reset request', 2, 2, 'L', 'CL', '2026-08-02-11.40.00'),
    (103, 'New laptop for onboarding', 3, 3, 'N', 'IP', '2026-08-03-14.05.00'),
    (104, 'VPN drops intermittently during video calls', 2, 1, 'N', 'OP', '2026-08-05-08.30.00')@

INSERT INTO ITSM.TICKET_COMMENT (COMMENT_ID, TICKET_ID, AUTHOR, COMMENT_TEXT, CREATED_AT) VALUES
    (1001, 101, 'helpdesk.alex', 'Confirmed VPN client version is outdated, sending update link.', '2026-08-01-09.40.00'),
    (1002, 102, 'helpdesk.priya', 'Password reset completed via self-service portal.', '2026-08-02-11.45.00'),
    (1003, 103, 'helpdesk.sam', 'Hardware order placed, ETA 3 business days.', '2026-08-03-14.20.00')@

INSERT INTO ITSM.SERVICE_CATALOG_NOTE (NOTE_ID, SERVICE_NAME, NOTE_TEXT) VALUES
    (1, 'VPN Access', 'Known issue: client v4.2 drops on Wi-Fi roam, upgrade to v4.3.'),
    (2, 'Password Reset', 'Self-service portal requires MFA enrollment before first use.')@
