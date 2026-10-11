package io.github.marekpompura.db2mcp.policy.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.marekpompura.db2mcp.policy.capability.CapabilityProfile;
import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import java.util.List;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.piped.FromQuery;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.TableStatement;
import net.sf.jsqlparser.statement.select.Values;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Adversarial catalogue item 11 ("large cross join or excessive nesting",
 * master brief section 7.6) - see docs/security/adversarial-test-cases.md.
 */
class SqlComplexityValidatorTest {

    private static final QueryComplexityLimitsProperties SAFE_LIMITS = new QueryComplexityLimitsProperties(3, 2);
    private static final AdvancedQueryComplexityLimitsProperties ADVANCED_LIMITS =
            new AdvancedQueryComplexityLimitsProperties(10, 5);

    private static SqlComplexityValidator newValidator(CapabilityProfile profile) {
        return new SqlComplexityValidator(new CapabilityProperties(profile), SAFE_LIMITS, ADVANCED_LIMITS);
    }

    private static final SqlComplexityValidator SAFE_QUERY_VALIDATOR = newValidator(CapabilityProfile.SAFE_QUERY);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM ITSM.TICKET",
                "SELECT * FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t.CUSTOMER_ID",
                "SELECT * FROM ITSM.TICKET t "
                        + "JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t.CUSTOMER_ID "
                        + "JOIN ITSM.SERVICE_CATALOG s ON s.SERVICE_ID = t.SERVICE_ID",
                "SELECT * FROM (SELECT * FROM ITSM.TICKET) t",
                "WITH x AS (SELECT * FROM ITSM.TICKET) SELECT * FROM x"
            })
    void validate_acceptsQueriesWithinLimits(String sql) {
        assertThatCode(() -> SAFE_QUERY_VALIDATOR.validate(sql)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM ITSM.TICKET t, ITSM.CUSTOMER c",
                "SELECT * FROM ITSM.TICKET t CROSS JOIN ITSM.CUSTOMER c"
            })
    void validate_rejectsCrossJoinsUnconditionally(String sql) {
        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cross join");
    }

    @Test
    void validate_rejectsCrossJoinEvenUnderAdvancedQuery() {
        // "Broader" never means a weaker guarantee (master brief section 6.3) -
        // cross-join rejection is categorical, not a configurable threshold.
        SqlComplexityValidator advanced = newValidator(CapabilityProfile.ADVANCED_QUERY);

        assertThatThrownBy(() -> advanced.validate("SELECT * FROM ITSM.TICKET t, ITSM.CUSTOMER c"))
                .hasMessageContaining("cross join");
    }

    @Test
    void validate_rejectsJoinCountOverTheLimit() {
        String sql = "SELECT * FROM ITSM.TICKET t "
                + "JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t.CUSTOMER_ID "
                + "JOIN ITSM.SERVICE_CATALOG s ON s.SERVICE_ID = t.SERVICE_ID "
                + "JOIN ITSM.REF_DATA r ON r.REF_CODE = t.STATUS "
                + "JOIN ITSM.SERVICE_CATALOG_NOTE n ON n.SERVICE_NAME = s.NAME";

        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("join count");
    }

    @Test
    void validate_sameQueryAcceptedUnderAdvancedQueryWithAHigherJoinCap() {
        String sql = "SELECT * FROM ITSM.TICKET t "
                + "JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t.CUSTOMER_ID "
                + "JOIN ITSM.SERVICE_CATALOG s ON s.SERVICE_ID = t.SERVICE_ID "
                + "JOIN ITSM.REF_DATA r ON r.REF_CODE = t.STATUS "
                + "JOIN ITSM.SERVICE_CATALOG_NOTE n ON n.SERVICE_NAME = s.NAME";
        SqlComplexityValidator advanced = newValidator(CapabilityProfile.ADVANCED_QUERY);

        assertThatCode(() -> advanced.validate(sql)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsNestingDepthOverTheLimit() {
        // 3 levels of parenthesized subquery, over SAFE_LIMITS' cap of 2
        String sql = "SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT * FROM ITSM.TICKET) a) b) c";

        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting depth");
    }

    // The regression-proving case: a FROM-only implementation would wrongly
    // accept this, since the cross join is hidden inside a WHERE EXISTS
    // subquery, not the top-level FROM clause.
    @Test
    void validate_rejectsCrossJoinHiddenInsideWhereExistsSubquery() {
        String sql = "SELECT * FROM ITSM.TICKET t "
                + "WHERE EXISTS (SELECT 1 FROM ITSM.CUSTOMER c, ITSM.SERVICE_CATALOG s)";

        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cross join");
    }

    @Test
    void validate_countsJoinsHiddenInsideWhereInSubqueryTowardTheCap() {
        // 4 joins inside the subquery, over SAFE_LIMITS' cap of 3 - the outer
        // query itself has none, so this only fails if the WHERE IN subquery
        // is genuinely walked, not just the top-level FROM clause.
        String sql = "SELECT * FROM ITSM.TICKET t WHERE t.TICKET_ID IN ("
                + "SELECT t2.TICKET_ID FROM ITSM.TICKET t2 "
                + "JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t2.CUSTOMER_ID "
                + "JOIN ITSM.SERVICE_CATALOG s ON s.SERVICE_ID = t2.SERVICE_ID "
                + "JOIN ITSM.REF_DATA r ON r.REF_CODE = t2.STATUS "
                + "JOIN ITSM.SERVICE_CATALOG_NOTE n ON n.SERVICE_NAME = s.NAME)";

        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("join count");
    }

    @Test
    void validate_unionBranchesDoNotAccumulateNestingDepth() {
        // Each branch is depth 1 (a sibling of the other), not depth 2.
        String sql = "SELECT * FROM ITSM.TICKET UNION SELECT * FROM ITSM.CUSTOMER";

        assertThatCode(() -> SAFE_QUERY_VALIDATOR.validate(sql)).doesNotThrowAnyException();
    }

    @Test
    void validate_cteBodyDoesNotAccumulateNestingDepth() {
        // The CTE body itself is depth 1 (a sibling), not depth 2 -
        // otherwise this would wrongly hit SAFE_LIMITS' depth cap of 2.
        String sql = "WITH x AS (SELECT * FROM (SELECT * FROM ITSM.TICKET) inner_q) SELECT * FROM x";

        assertThatCode(() -> SAFE_QUERY_VALIDATOR.validate(sql)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsUnparseableSql_failClosed() {
        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate("not valid sql at all"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsAParseableNonSelectStatement() {
        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate("DELETE FROM ITSM.TICKET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a SELECT/WITH statement");
    }

    @Test
    void validate_acceptsAHavingClause() {
        String sql = "SELECT STATUS, COUNT(*) FROM ITSM.TICKET GROUP BY STATUS HAVING COUNT(*) > 10";

        assertThatCode(() -> SAFE_QUERY_VALIDATOR.validate(sql)).doesNotThrowAnyException();
    }

    @Test
    void validate_acceptsAParenthesizedFromItemContainingAJoin() {
        String sql = "SELECT * FROM (ITSM.TICKET t JOIN ITSM.CUSTOMER c ON c.CUSTOMER_ID = t.CUSTOMER_ID)";

        assertThatCode(() -> SAFE_QUERY_VALIDATOR.validate(sql)).doesNotThrowAnyException();
    }

    // Real JSqlParser dispatch never reaches LateralSubSelect/Values/
    // SetOperationList/TableStatement/FromQuery/the zero-arg interface-
    // default-conflict overrides through any SQL this validator's own
    // structural/schema gates would ever let through (a LATERAL subselect
    // reachable from the FROM clause isn't produced by JSqlParser's own
    // grammar the way this validator would need to trigger it, and
    // FromQuery is piped-SQL syntax rejected upstream). Exercising
    // ComplexityWalker directly - now package-private for exactly this
    // reason - is the only way to get real coverage of that dispatch code,
    // matching its own class-level javadoc.
    @Test
    void complexityWalker_zeroArgOverridesDelegateToTheirTwoArgVersions() {
        SqlComplexityValidator.ComplexityWalker walker = new SqlComplexityValidator.ComplexityWalker(1000, 1000);

        ParenthesedSelect parenthesedSelect = mock(ParenthesedSelect.class);
        when(parenthesedSelect.getSelect()).thenReturn(mock(Select.class));
        assertThatCode(() -> walker.visit(parenthesedSelect)).doesNotThrowAnyException();

        LateralSubSelect lateralSubSelect = mock(LateralSubSelect.class);
        when(lateralSubSelect.getSelect()).thenReturn(mock(Select.class));
        assertThatCode(() -> walker.visit(lateralSubSelect)).doesNotThrowAnyException();

        assertThatCode(() -> walker.visit(mock(Values.class))).doesNotThrowAnyException();

        assertThatCode(() -> walker.visit(mock(PlainSelect.class))).doesNotThrowAnyException();

        SetOperationList setOperationList = mock(SetOperationList.class);
        when(setOperationList.getSelects()).thenReturn(List.of());
        assertThatCode(() -> walker.visit(setOperationList)).doesNotThrowAnyException();

        assertThatCode(() -> walker.visit(mock(TableStatement.class))).doesNotThrowAnyException();
    }

    @Test
    void complexityWalker_ignoresFromQuery_pipedSqlSyntaxNotReachableThroughRealParsing() {
        SqlComplexityValidator.ComplexityWalker walker = new SqlComplexityValidator.ComplexityWalker(1000, 1000);

        assertThatCode(() -> walker.visit(mock(FromQuery.class), null)).doesNotThrowAnyException();
    }

    @Test
    void complexityWalker_ignoresTableFunction() {
        SqlComplexityValidator.ComplexityWalker walker = new SqlComplexityValidator.ComplexityWalker(1000, 1000);

        assertThatCode(() -> walker.visit(mock(TableFunction.class), null)).doesNotThrowAnyException();
    }

    // JSqlParser's own grammar never actually produces a Join with a null
    // getFromItem()/getOnExpressions(), or a PlainSelect with null select
    // items - every real parse populates them. These defensive null checks
    // guard against a hypothetical AST shape, not one reachable through
    // CCJSqlParserUtil.parse(...), so mocking a hostile AST directly is the
    // only way to exercise the "child is missing" branch.
    @Test
    void complexityWalker_toleratesNullFromItemAndOnExpressionsInAJoin() {
        SqlComplexityValidator.ComplexityWalker walker = new SqlComplexityValidator.ComplexityWalker(1000, 1000);
        Join join = mock(Join.class);
        when(join.getOnExpressions()).thenReturn(null);
        PlainSelect plainSelect = mock(PlainSelect.class);
        when(plainSelect.getSelectItems()).thenReturn(null);
        when(plainSelect.getJoins()).thenReturn(List.of(join));

        assertThatCode(() -> walker.visit(plainSelect, null)).doesNotThrowAnyException();
    }

    @Test
    void complexityWalker_toleratesAParenthesizedFromItemWithNoFromItem() {
        SqlComplexityValidator.ComplexityWalker walker = new SqlComplexityValidator.ComplexityWalker(1000, 1000);

        assertThatCode(() -> walker.visit(mock(ParenthesedFromItem.class), null)).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void complexityWalker_toleratesASelectItemWithNoExpression() {
        SqlComplexityValidator.ComplexityWalker walker = new SqlComplexityValidator.ComplexityWalker(1000, 1000);
        SelectItem<Expression> selectItem = mock(SelectItem.class);

        assertThatCode(() -> walker.visit(selectItem, null)).doesNotThrowAnyException();
    }

    // ADR 0035: a join whose ON clause is structurally always true produces
    // the same unrestricted Cartesian product as a cross join, but evades
    // Join.isCross()/isSimple() since it's a syntactically normal join.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON 1=1",
                "SELECT * FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON TRUE",
                "SELECT * FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON t.TICKET_ID = t.TICKET_ID"
            })
    void validate_rejectsTautologicalOnPredicate(String sql) {
        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("always true");
    }

    @Test
    void validate_rejectsTautologicalOnPredicateEvenUnderAdvancedQuery() {
        // Categorical, not a configurable threshold - same "broader never
        // means weaker" principle as the cross-join check right above it.
        SqlComplexityValidator advanced = newValidator(CapabilityProfile.ADVANCED_QUERY);

        assertThatThrownBy(() -> advanced.validate("SELECT * FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON 1=1"))
                .hasMessageContaining("always true");
    }

    @Test
    void validate_acceptsAndClauseWithOneTautologicalAndOneRealConjunct() {
        // AND requires BOTH conjuncts tautological to reject - a harmless
        // redundant "1=1" ANDed with a real restricting predicate does not,
        // in fact, produce an unrestricted product.
        String sql = "SELECT * FROM ITSM.TICKET t "
                + "JOIN ITSM.CUSTOMER c ON 1=1 AND c.CUSTOMER_ID = t.CUSTOMER_ID";

        assertThatCode(() -> SAFE_QUERY_VALIDATOR.validate(sql)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsAndClauseWhereBothConjunctsAreTautological() {
        String sql = "SELECT * FROM ITSM.TICKET t JOIN ITSM.CUSTOMER c ON 1=1 AND TRUE";

        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate(sql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("always true");
    }

    @ParameterizedTest
    @CsvSource({
        "1=1, true",
        "TRUE, true",
        "FALSE, false",
        "t.ID = t.ID, true",
        "1=1 AND TRUE, true",
        "1=1 AND t.ID = c.ID, false",
        "t.ID = c.ID, false",
        "1=2, false"
    })
    void isTautological_directUnitTests(String conditionSql, boolean expected) throws Exception {
        Expression expression = CCJSqlParserUtil.parseCondExpression(conditionSql);

        assertThat(SqlComplexityValidator.ComplexityWalker.isTautological(expression)).isEqualTo(expected);
    }

    // ADR 0035: piped SQL (e.g. "FROM t |> CROSS JOIN ...") parses as a
    // Select - confirmed directly against JSqlParser 5.3 - so it is NOT
    // caught by the "not a SELECT/WITH statement" check, but exposes none
    // of its joins to this walker and none of its tables to
    // SchemaAllowlistValidator's TablesNamesFinder either. Left unrejected,
    // it would silently bypass cross-join/join-count/nesting-depth limits
    // and the schema allowlist simultaneously.
    @Test
    void validate_rejectsPipedSqlSyntax() {
        assertThatThrownBy(() -> SAFE_QUERY_VALIDATOR.validate("SELECT * FROM ITSM.TICKET t |> CROSS JOIN ITSM.CUSTOMER c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("piped SQL");
    }
}
