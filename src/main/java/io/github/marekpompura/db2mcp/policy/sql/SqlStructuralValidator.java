package io.github.marekpompura.db2mcp.policy.sql;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.piped.FromQuery;
import net.sf.jsqlparser.statement.select.PivotQuery;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

/**
 * Structural SQL validation for executeQuery (ADR 0007), layered on top of -
 * not replacing - the regex prefix/semicolon guard already in
 * Db2QueryAdapter. Two independent checks catch different failure classes,
 * same reasoning as the three-layer read-only enforcement in ADR 0002.
 *
 * DB2-specific syntax (e.g. FINAL TABLE/OLD TABLE/NEW TABLE) is not
 * recognized by JSqlParser's generic ANSI grammar and will fail to parse.
 * That is treated as a rejection (fail-closed), not a bug to work around.
 *
 * Piped SQL syntax (e.g. {@code FROM t |> CROSS JOIN ...}) is explicitly
 * rejected too (ADR 0035): it parses as a {@code Select} (a {@code
 * FromQuery}), so it is NOT caught by the check above, but exposes none of
 * its table references to {@code SchemaAllowlistValidator}'s {@code
 * TablesNamesFinder} and none of its joins to {@code SqlComplexityValidator}
 * - confirmed directly against JSqlParser 5.3, not assumed. Left unrejected,
 * it would silently bypass the schema allowlist and complexity limits both.
 */
@Component
public class SqlStructuralValidator {

    public void validate(String sql) {
        // CCJSqlParserUtil.parse(String) only parses the first statement and silently
        // ignores trailing text - "SELECT 1; DROP TABLE X" parses "successfully" as
        // just the SELECT. That was discovered by a test aimed at exactly this case:
        // this validator was NOT actually an independent second layer against
        // multi-statement input as ADR 0007 originally assumed, only the regex guard
        // in Db2QueryAdapter was. parseStatements() sees the full input and is used
        // here specifically to make that independence real.
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("SQL failed structural validation: " + e.getMessage(), e);
        }
        if (statements == null || statements.size() != 1) {
            throw new IllegalArgumentException(
                    "SQL failed structural validation: exactly one statement is required, found "
                            + (statements == null ? 0 : statements.size()));
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException(
                    "Only SELECT/WITH statements are allowed, parsed as: " + statement.getClass().getSimpleName());
        }
        if (statement instanceof FromQuery) {
            throw new IllegalArgumentException("SQL failed structural validation: piped SQL syntax is not supported");
        }
        // PivotQuery is new in JSqlParser 5.4 (#100) and extends Select, so
        // the instanceof check above passes it - structurally the same
        // bypass FromQuery was rejected for above. Verified against 5.4:
        // "PIVOT ITSM.TICKET ON STATUS USING COUNT(ID)" parses as a
        // top-level PivotQuery. TablesNamesFinder does still report its
        // tables, so the schema allowlist would hold - but
        // SqlComplexityValidator's walker sees only the interface's no-op
        // default for this type, so any joins or nesting inside a pivot
        // would go uncounted.
        //
        // THE REASON IS THE UNMODELLED SHAPE, NOT THE DIALECT. This check
        // is vendor-neutral on purpose: the policy chain rejects SQL whose
        // structure it cannot walk, whatever database is behind the port.
        // Do not delete it on the grounds that some other database supports
        // PIVOT - that would reinstate the bypass for exactly the adapters
        // that can actually execute it. The correct way to support PIVOT is
        // to teach ComplexityWalker to traverse PivotQuery's FromItem, after
        // which this rejection becomes unnecessary for every adapter at once.
        //
        // Db2 evidence (2026-09-18, real Db2 12.1.5.0 container): all pivot
        // forms are rejected by the database itself with SQL0104N /
        // SQLSTATE=42601, including the exact statement that reached this
        // check. JSqlParser is dialect-agnostic and parses DuckDB/Oracle/
        // SQL-Server syntax Db2 never implemented. So today the rejection
        // costs no capability at all - it only turns a database syntax
        // error into an earlier, clearer policy message.
        if (statement instanceof PivotQuery) {
            throw new IllegalArgumentException("SQL failed structural validation: PIVOT syntax is not supported");
        }
    }
}
