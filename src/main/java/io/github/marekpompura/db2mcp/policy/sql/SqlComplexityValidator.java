package io.github.marekpompura.db2mcp.policy.sql;

import io.github.marekpompura.db2mcp.policy.capability.CapabilityProperties;
import java.util.Collection;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.imprt.Import;
import net.sf.jsqlparser.statement.piped.FromQuery;
import net.sf.jsqlparser.statement.select.FromItemVisitor;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PivotQuery;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitor;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.TableStatement;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Component;

/**
 * Deterministic structural complexity limits (ADR 0034, extended by ADR
 * 0035): rejects cross joins unconditionally, and caps total join count
 * and subquery nesting depth across the WHOLE statement tree - not just
 * the top level, which would be trivially bypassed by hiding joins inside
 * a FROM-clause or WHERE-clause subquery. Also rejects (ADR 0035) a join
 * whose ON clause is structurally tautological (e.g. {@code ON 1=1}),
 * which would otherwise produce the same unrestricted Cartesian product
 * as a cross join while evading {@code Join.isCross()}/{@code isSimple()};
 * and piped SQL syntax (e.g. {@code FROM t |> CROSS JOIN ...}), which
 * parses as a {@code Select} but exposes none of its joins to this
 * walker or its tables to {@link SchemaAllowlistValidator}. Runs after
 * {@link SqlStructuralValidator} and {@link SchemaAllowlistValidator} in
 * {@code Db2QueryAdapter}'s guard chain.
 */
@Component
public class SqlComplexityValidator {

    private final ResolvedComplexityLimits limits;

    public SqlComplexityValidator(
            CapabilityProperties capabilityProperties,
            QueryComplexityLimitsProperties base,
            AdvancedQueryComplexityLimitsProperties advanced) {
        this.limits = ResolvedComplexityLimits.of(capabilityProperties.capabilityProfile(), base, advanced);
    }

    public void validate(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("SQL failed complexity validation: " + e.getMessage(), e);
        }
        if (!(statement instanceof Select select)) {
            throw new IllegalArgumentException("SQL failed complexity validation: not a SELECT/WITH statement");
        }
        if (select instanceof FromQuery) {
            // Piped-SQL syntax (e.g. "FROM t |> CROSS JOIN ...") IS a Select,
            // so it passes the check above - but TablesNamesFinder returns no
            // tables for it and this walker never sees its joins either (see
            // the FromQuery visit method below), so leaving it unrejected here
            // would silently bypass cross-join/join-count/nesting-depth limits
            // entirely. Same fail-closed treatment SqlStructuralValidator
            // already gives FINAL TABLE/OLD TABLE/NEW TABLE for an unsupported
            // shape, not a semantic analysis of what the piped query does.
            throw new IllegalArgumentException("SQL failed complexity validation: piped SQL syntax is not supported");
        }
        if (select instanceof PivotQuery) {
            // Mirrors SqlStructuralValidator's rejection (#100), for the
            // same reason FromQuery is mirrored here: this validator must
            // stand on its own rather than assume an earlier gate ran.
            // See that class for why this is about an unmodelled shape
            // rather than about Db2's dialect - it stays correct for a
            // future adapter whose database does support PIVOT.
            throw new IllegalArgumentException("SQL failed complexity validation: PIVOT syntax is not supported");
        }
        // A fresh instance per call - this validator bean is a concurrent
        // Spring singleton, so join/depth counters must never live on it.
        ComplexityWalker walker = new ComplexityWalker(limits.maxJoins(), limits.maxNestingDepth());
        select.accept((SelectVisitor<Void>) walker, null);
    }

    /**
     * Walks the whole statement tree once. JSqlParser's visitor {@code
     * .accept()} never auto-recurses - every child that should be walked
     * must be visited explicitly (confirmed by reading {@code
     * TablesNamesFinder}'s own source, the existing precedent {@link
     * SchemaAllowlistValidator} already relies on for table-reference
     * discovery). This class mirrors that same dispatch shape, but counts
     * joins/nesting instead of collecting table names, and additionally
     * composes an {@link ExpressionVisitorAdapter} (wired via {@code
     * setSelectVisitor(this)}) to reach subqueries hidden in WHERE/HAVING/
     * ON/EXISTS/IN/a scalar comparison - {@code ExpressionVisitorAdapter}
     * already implements that recursion internally and delegates any
     * {@code Select} it finds straight back here, so no hand-written
     * {@code ExpressionVisitor} overrides are needed.
     *
     * <p>Nesting depth increments only for genuinely nested subqueries
     * (a parenthesized FROM-clause/expression subquery, or a LATERAL
     * subquery) - CTE bodies and {@code UNION}/{@code INTERSECT}/{@code
     * EXCEPT} branches are siblings, not nesting, and don't increment it.
     */
    // Package-private, not private: ComplexityValidatorTest directly exercises
    // a handful of methods here that real SQL parsing can never reach (the
    // zero-arg delegate overrides below exist only to resolve a Java
    // interface-default conflict - nothing in this class's own dispatch ever
    // calls them), so real unit-test coverage of them requires direct access.
    static final class ComplexityWalker
            implements SelectVisitor<Void>, FromItemVisitor<Void>, SelectItemVisitor<Void> {

        private final int maxJoins;
        private final int maxNestingDepth;
        private final ExpressionVisitorAdapter<Void> expressionVisitor = new ExpressionVisitorAdapter<>();
        private int totalJoins = 0;
        private int currentDepth = 0;

        ComplexityWalker(int maxJoins, int maxNestingDepth) {
            this.maxJoins = maxJoins;
            this.maxNestingDepth = maxNestingDepth;
            expressionVisitor.setSelectVisitor(this);
        }

        private void enterNesting() {
            currentDepth++;
            if (currentDepth > maxNestingDepth) {
                throw new IllegalArgumentException(
                        "SQL failed complexity validation: subquery nesting depth " + currentDepth
                                + " exceeds the maximum of " + maxNestingDepth);
            }
        }

        private void exitNesting() {
            currentDepth--;
        }

        private void visitJoins(java.util.List<Join> joins) {
            if (joins == null) {
                return;
            }
            for (Join join : joins) {
                // isSimple() is a plain comma-separated FROM item ("T1, T2",
                // no JOIN keyword, no ON/USING) - an unrestricted Cartesian
                // product exactly like an explicit CROSS JOIN, just written
                // differently. Both are rejected the same way (master brief
                // section 7.5: "reject pathological cross joins").
                if (join.isCross() || join.isSimple()) {
                    throw new IllegalArgumentException("SQL failed complexity validation: cross joins are not allowed");
                }
                Collection<Expression> onExpressions = join.getOnExpressions();
                if (onExpressions != null) {
                    for (Expression expression : onExpressions) {
                        if (isTautological(expression)) {
                            throw new IllegalArgumentException("SQL failed complexity validation: join ON clause is "
                                    + "always true, producing an unrestricted Cartesian product");
                        }
                    }
                }
                totalJoins++;
                if (totalJoins > maxJoins) {
                    throw new IllegalArgumentException(
                            "SQL failed complexity validation: join count " + totalJoins
                                    + " exceeds the maximum of " + maxJoins);
                }
                if (join.getFromItem() != null) {
                    join.getFromItem().accept(this, null);
                }
                if (onExpressions != null) {
                    for (Expression expression : onExpressions) {
                        expression.accept(expressionVisitor, null);
                    }
                }
            }
        }

        // Deliberately simple and structural (string-equality of both sides
        // of an equals comparison), not semantic - master brief section 7.3
        // gates this whole feature class on being "deterministic enough to
        // explain and test," which rules out anything resembling a SAT
        // solver. AND decomposes (both conjuncts must be tautological to
        // reject - "ON 1=1 AND c.id=t.id" is a real, if inelegant,
        // restricting predicate and must NOT be flagged); OR does not
        // decompose (a documented, narrow residual gap - ADR 0035).
        static boolean isTautological(Expression expression) {
            if (expression instanceof AndExpression and) {
                return isTautological(and.getLeftExpression()) && isTautological(and.getRightExpression());
            }
            if (expression instanceof BooleanValue bool) {
                return bool.getValue();
            }
            if (expression instanceof EqualsTo eq) {
                return eq.getLeftExpression().toString().equals(eq.getRightExpression().toString());
            }
            return false;
        }

        private void visitWithItems(java.util.List<WithItem<?>> withItemsList) {
            if (withItemsList == null) {
                return;
            }
            for (WithItem<?> withItem : withItemsList) {
                // Routed through withItem.accept(...) (not
                // withItem.getSelect().accept(...) directly) so this class's
                // own visit(WithItem<?>, S) override below is genuinely
                // exercised, not bypassed.
                withItem.accept((SelectVisitor<?>) this, null);
            }
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            visitWithItems(plainSelect.getWithItemsList());
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    item.accept(this, null);
                }
            }
            if (plainSelect.getFromItem() != null) {
                plainSelect.getFromItem().accept(this, null);
            }
            visitJoins(plainSelect.getJoins());
            if (plainSelect.getWhere() != null) {
                plainSelect.getWhere().accept(expressionVisitor, null);
            }
            if (plainSelect.getHaving() != null) {
                plainSelect.getHaving().accept(expressionVisitor, null);
            }
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            enterNesting();
            try {
                visitWithItems(select.getWithItemsList());
                select.getSelect().accept((SelectVisitor<?>) this, null);
            } finally {
                exitNesting();
            }
            return null;
        }

        @Override
        public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
            enterNesting();
            try {
                lateralSubSelect.getSelect().accept((SelectVisitor<?>) this, null);
            } finally {
                exitNesting();
            }
            return null;
        }

        @Override
        public <S> Void visit(SetOperationList setOpList, S context) {
            // UNION/INTERSECT/EXCEPT branches are siblings, not nested.
            visitWithItems(setOpList.getWithItemsList());
            for (Select branch : setOpList.getSelects()) {
                branch.accept((SelectVisitor<?>) this, null);
            }
            return null;
        }

        @Override
        public <S> Void visit(WithItem<?> withItem, S context) {
            withItem.getSelect().accept((SelectVisitor<?>) this, null);
            return null;
        }

        // Deliberately empty (issue #42, P1-5: SpotBugs flags this and
        // visit(Values, S) below as RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT,
        // since the delegating zero-arg overrides below ignore this
        // method's always-null Void return - investigated, not suppressed
        // blind: TableStatement is JSqlParser's umbrella interface type for
        // statement kinds this walker already handles via more specific
        // overrides (PlainSelect, SetOperationList, WithItem) - a bare
        // TableStatement node carries no join/nesting structure of its own
        // to walk, so there is genuinely nothing for this override to do.
        @Override
        public <S> Void visit(TableStatement tableStatement, S context) {
            return null;
        }

        // Below: zero-arg overrides required because SelectVisitor and
        // FromItemVisitor each declare a conflicting `default void
        // visit(X x)` for these six overlapping node types - Java can't
        // pick one interface's default over the other's, so both must be
        // resolved explicitly here, each just delegating to this class's
        // own two-arg override above with a null context.
        @Override
        public void visit(ParenthesedSelect parenthesedSelect) {
            this.visit(parenthesedSelect, null);
        }

        @Override
        public void visit(LateralSubSelect lateralSubSelect) {
            this.visit(lateralSubSelect, null);
        }

        @Override
        public void visit(Values values) {
            this.visit(values, null);
        }

        @Override
        public <S> Void visit(PivotQuery pivotQuery, S context) {
            // New in JSqlParser 5.4 (#100). PivotQuery extends Select, so
            // it reaches neither validator's walker: both reject it up
            // front. A safe no-op recording that this walker does NOT
            // understand a pivot's contents - if the rejection above were
            // ever relaxed, this would need real traversal first.
            return null;
        }

        @Override
        public void visit(PivotQuery pivotQuery) {
            this.visit(pivotQuery, null);
        }

        @Override
        public void visit(Import importStatement) {
            // Same zero-arg delegate shape as its neighbours (#100). New in
            // JSqlParser 5.4: FromItemVisitor gained both visit(Import, S)
            // and a default void visit(Import), and a class implementing
            // two visitor interfaces has to override the zero-arg default
            // explicitly - that inherited-default clash is what broke the
            // build, not the two-arg method alone.
            this.visit(importStatement, null);
        }

        @Override
        public void visit(PlainSelect plainSelect) {
            this.visit(plainSelect, null);
        }

        @Override
        public void visit(SetOperationList setOpList) {
            this.visit(setOpList, null);
        }

        @Override
        public void visit(TableStatement tableStatement) {
            this.visit(tableStatement, null);
        }

        // Deliberately empty, same reasoning as visit(TableStatement, S)
        // above: a VALUES(...) literal list has no nested join/subquery
        // structure to walk for complexity purposes.
        @Override
        public <S> Void visit(Values values, S context) {
            return null;
        }

        @Override
        public <S> Void visit(Import importStatement, S context) {
            // New in JSqlParser 5.4 (#100): Import was added to
            // FromItemVisitor, which is why this override exists at all -
            // the interface requires it. It implements both FromItem and
            // Statement, and carries a table, a column list and a
            // file/DBMS source: a data-loading construct.
            //
            // Two independent reasons real parsing does not reach here,
            // both checked rather than assumed:
            //
            //   1. As a statement it is not a Select, so
            //      SqlStructuralValidator rejects it before this walker is
            //      ever constructed.
            //   2. Against 5.4's own parser, no syntax could be found that
            //      produces an Import at all - six candidate forms
            //      (statement and FROM-clause shapes) were all rejected by
            //      CCJSqlParserUtil.parse. The type is reachable through
            //      the visitor API but its grammar appears not to be wired
            //      up in this release.
            //
            // Point 2 is deliberately phrased as "could not be reached",
            // not "cannot be reached" - the honest claim. If a later
            // JSqlParser wires the syntax up, reason 1 still holds for the
            // statement form, but the FROM-clause form would want
            // rechecking against SqlStructuralValidator.
            //
            // A safe no-op, like the FromQuery override below, not a live
            // guarantee that this class defends anything.
            return null;
        }

        @Override
        public <S> Void visit(FromQuery fromQuery, S context) {
            // Piped-SQL syntax IS a Select (confirmed directly against
            // JSqlParser 5.3 - it is not rejected by an instanceof check),
            // but validate() rejects it before this walker is ever
            // constructed, so real parsing never reaches this override
            // either way. Kept as a safe no-op, not a live guarantee.
            return null;
        }

        @Override
        public <S> Void visit(Table table, S context) {
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedFromItem parenthesis, S context) {
            if (parenthesis.getFromItem() != null) {
                parenthesis.getFromItem().accept(this, null);
            }
            visitJoins(parenthesis.getJoins());
            return null;
        }

        @Override
        public <S> Void visit(TableFunction tableFunction, S context) {
            return null;
        }

        @Override
        public <S> Void visit(SelectItem<? extends Expression> selectItem, S context) {
            if (selectItem.getExpression() != null) {
                selectItem.getExpression().accept(expressionVisitor, null);
            }
            return null;
        }
    }
}
