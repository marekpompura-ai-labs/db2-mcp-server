package io.github.marekpompura.db2mcp.domain.model;

/**
 * Which database product an adapter implements (database-portability
 * extension section 5.1, ADR 0028). Deliberately contains only {@code DB2}
 * today - a future value must not be added until a real adapter and
 * integration evidence exist for it (extension section 3.3: no Oracle/
 * PostgreSQL support claimed based on H2 tests alone). Its single-value
 * closed set is also the fail-fast mechanism for
 * {@code DatabaseSelectionProperties}: any other configured value simply
 * cannot bind, so Spring Boot's own enum-conversion failure rejects it at
 * startup with no custom validation code needed.
 */
public enum DatabaseProduct {
    DB2
}
