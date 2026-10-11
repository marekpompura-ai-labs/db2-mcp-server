package io.github.marekpompura.db2mcp.policy.sql;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Normalized, non-sensitive summary of a SQL statement for audit logging
 * (ADR 0010) - never the raw SQL text, never literal values or column
 * names. Best-effort: never throws, since it must remain usable for
 * logging even when the input already failed validation elsewhere.
 */
@Component
public class SqlFingerprint {

    private static final String UNPARSEABLE = "UNPARSEABLE";

    public String of(String sql) {
        if (sql == null) {
            return UNPARSEABLE;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement == null) {
                return UNPARSEABLE;
            }
            List<String> tables = new TablesNamesFinder().getTableList(statement);
            String sortedTables = new TreeSet<>(tables).stream()
                    .map(t -> t.replace("\"", "").toUpperCase(Locale.ROOT))
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            String statementType = statement.getClass().getSimpleName().toUpperCase(Locale.ROOT);
            return statementType + "[" + sortedTables + "]";
        } catch (Exception e) {
            return UNPARSEABLE;
        }
    }
}
