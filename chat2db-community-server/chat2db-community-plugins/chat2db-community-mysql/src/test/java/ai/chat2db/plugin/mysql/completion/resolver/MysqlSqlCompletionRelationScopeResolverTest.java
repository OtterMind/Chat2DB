package ai.chat2db.plugin.mysql.completion.resolver;

import ai.chat2db.plugin.mysql.model.completion.scope.MysqlSqlCompletionRelationScope;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatementWindowTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCursorContext;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionMetadataScope;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionStatementWindow;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MysqlSqlCompletionRelationScopeResolverTest {

    @Test
    void resolvesTableAlias() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from orders o where o.{caret}");

        assertRelations(scope, relation(null, null, "orders", "o"));
    }

    @Test
    void resolvesSchemaQualifiedTableAlias() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from archive.orders o where o.{caret}");

        assertRelations(scope, relation(null, "archive", "orders", "o"));
    }

    @Test
    void resolvesCatalogAndSchemaQualifiedTableAlias() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from prod.archive.orders o where o.{caret}");

        assertRelations(scope, relation("prod", "archive", "orders", "o"));
    }

    @Test
    void keepsJoinAliasesSeparated() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from users u join orders o on u.id = o.user_id where {caret}");

        assertRelations(scope,
                relation(null, null, "users", "u"),
                relation(null, null, "orders", "o"));
    }

    @Test
    void resolvesCommaRelationAfterJoinPredicate() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from users u join orders o on u.id = o.user_id, payments p where p.{caret}");

        assertRelations(scope,
                relation(null, null, "users", "u"),
                relation(null, null, "orders", "o"),
                relation(null, null, "payments", "p"));
    }

    @Test
    void doesNotReuseFromFromSiblingSubqueryForProjectionComma() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select (select id from users u), (select x, y, z{caret} from orders o)");

        assertRelations(scope, relation(null, null, "orders", "o"));
        Assertions.assertTrue(scope.resolveOwner("y").isEmpty());
    }

    @Test
    void doesNotReuseFromFromPreviousCteBodyForProjectionComma() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "with first_cte as (select id from users u), "
                        + "second_cte as (select x, y, z{caret} from orders o) select * from second_cte");

        assertRelations(scope, relation(null, null, "orders", "o"));
        Assertions.assertTrue(scope.resolveOwner("y").isEmpty());
    }

    @Test
    void doesNotReuseFromFromPreviousStatementForProjectionComma() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select id from users u; select x, y, z{caret} from orders o");

        assertRelations(scope, relation(null, null, "orders", "o"));
        Assertions.assertTrue(scope.resolveOwner("y").isEmpty());
    }

    @Test
    void doesNotTreatOnDuplicateKeyUpdateCommaAsRelation() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "insert into dst (id, v) select s.id, s.v from src s "
                        + "on duplicate key update id = values(id), v = s.{caret}");

        assertRelations(scope,
                relation(null, null, "dst", null),
                relation(null, null, "src", "s"));
        Assertions.assertTrue(scope.resolveOwner("v").isEmpty());
    }

    @Test
    void doesNotTreatForUpdateCommaAsRelation() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from users u join orders o on u.id = o.user_id for update of u, o.{caret}");

        assertRelations(scope,
                relation(null, null, "users", "u"),
                relation(null, null, "orders", "o"));
    }

    @Test
    void doesNotTreatForShareCommaAsRelation() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from users u join orders o on u.id = o.user_id for share of u, o.{caret}");

        assertRelations(scope,
                relation(null, null, "users", "u"),
                relation(null, null, "orders", "o"));
    }

    @Test
    void doesNotTreatNamedWindowAsRelation() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from users u window w1 as (order by u.id), w2 as (order by u.id) "
                        + "order by u.{caret}");

        assertRelations(scope, relation(null, null, "users", "u"));
        Assertions.assertTrue(scope.resolveOwner("w2").isEmpty());
    }

    @Test
    void doesNotTreatIndexHintAsAlias() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from orders force index for join (idx_orders_status) where {caret}");

        assertRelations(scope, relation(null, null, "orders", null));
    }

    @Test
    void skipsClosedSubqueryPhysicalRelationsAndKeepsDerivedTable() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select * from (select id, status from orders) d where d.{caret}");

        assertRelations(scope, localRelation("d", "d", List.of("id", "status")));
        Assertions.assertTrue(scope.resolveOwner("orders").isEmpty());
    }

    @Test
    void cteAliasUsesProjectedColumnsWithoutLeakingInnerTable() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "with recent as (select id, status from orders) select * from recent r where r.{caret}");

        List<MysqlSqlCompletionRelationScope.Relation> ownerRelations = scope.resolveOwner("r");
        Assertions.assertEquals(List.of(localRelation("recent", "r", List.of("id", "status"))), ownerRelations);
        Assertions.assertTrue(scope.resolveOwner("orders").isEmpty());
    }

    @Test
    void cteBeforeMainSelectAcrossNewlinesStaysVisibleForTablePrefix() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret("""
                WITH paid_orders as(
                SELECT * FROM orders
                WHERE status='paid'
                )
                SELECT * from pa{caret}""");

        assertRelations(scope, localRelation("paid_orders", null, List.of()));
    }

    @Test
    void projectionBeforeFromOnlyScansCurrentSelectRange() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "with x as (select st{caret} from orders) select * from users");

        assertRelations(scope, relation(null, null, "orders", null));
        Assertions.assertTrue(scope.resolveOwner("users").isEmpty());
    }

    @Test
    void projectionBeforeFromCanSeeForwardDerivedTableInCurrentSelect() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select st{caret} from (select status as st from orders) d");

        assertRelations(scope, localRelation("d", "d", List.of("st")));
        Assertions.assertTrue(scope.resolveOwner("orders").isEmpty());
    }

    @Test
    void expressionNestedInFunctionCanSeeForwardFromRelation() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select coalesce(st{caret}) from orders o");

        assertRelations(scope, relation(null, null, "orders", "o"));
    }

    @Test
    void qualifiedFunctionArgumentCanResolveForwardAlias() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select count(distinct o.{caret}) from orders o");

        assertRelations(scope, relation(null, null, "orders", "o"));
        Assertions.assertEquals(List.of(relation(null, null, "orders", "o")), scope.resolveOwner("o"));
    }

    @Test
    void semicolonStopsForwardFromScan() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select st{caret} from orders; select email from users");

        assertRelations(scope, relation(null, null, "orders", null));
        Assertions.assertTrue(scope.resolveOwner("users").isEmpty());
    }

    @Test
    void correlatedSubqueryCanSeeOuterRelationAlias() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "select u.id from users u where exists (select 1 from orders o where o.customer_id = u.{caret})");

        assertRelations(scope,
                relation(null, null, "users", "u"),
                relation(null, null, "orders", "o"));
    }

    @Test
    void referencesScopeOnlyAppliesInsideReferenceColumnList() {
        String sql = "create table child (parent_id bigint, constraint fk foreign key (parent_id) "
                + "references parent(id), st int)";

        MysqlSqlCompletionRelationScope insideScope = resolve(sql, sql.lastIndexOf("id)") + 2);
        MysqlSqlCompletionRelationScope afterScope = resolve(sql, sql.indexOf("st"));

        assertRelations(insideScope, relation(null, null, "parent", null));
        Assertions.assertTrue(afterScope.relations().isEmpty());
    }

    @Test
    void alterTableColumnListUsesAlterTarget() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "alter table archive.orders add index idx_status (st{caret})");

        assertRelations(scope, relation(null, "archive", "orders", null));
    }

    @Test
    void createIndexColumnListUsesTargetTable() {
        MysqlSqlCompletionRelationScope scope = resolveAtCaret(
                "create index idx_status on archive.orders (st{caret})");

        assertRelations(scope, relation(null, "archive", "orders", null));
    }

    private MysqlSqlCompletionRelationScope resolveAtCaret(String sqlWithCaret) {
        int cursor = sqlWithCaret.indexOf("{caret}");
        Assertions.assertTrue(cursor >= 0, "missing caret marker");
        String sql = sqlWithCaret.replace("{caret}", "");
        return resolve(sql, cursor);
    }

    private MysqlSqlCompletionRelationScope resolve(String sql, int cursor) {
        int replaceStart = identifierPartStart(sql, cursor);
        String prefix = sql.substring(replaceStart, cursor);
        return MysqlSqlCompletionRelationScopeResolver.resolve(
                new SqlCompletionStatementWindow(sql, sql, 0, sql.length(), cursor,
                        SqlCompletionStatementWindowTypeEnum.CURRENT_STATEMENT.name()),
                SqlCompletionCursorContext.admitted(SqlCompletionMetadataScope.empty(), prefix, replaceStart,
                        cursor, false));
    }

    private int identifierPartStart(String sql, int cursor) {
        int index = Math.max(0, Math.min(cursor, sql.length()));
        while (index > 0) {
            char ch = sql.charAt(index - 1);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '$') {
                break;
            }
            index--;
        }
        return index;
    }

    private void assertRelations(MysqlSqlCompletionRelationScope scope,
                                 MysqlSqlCompletionRelationScope.Relation... expected) {
        Assertions.assertEquals(List.of(expected), scope.relations());
    }

    private MysqlSqlCompletionRelationScope.Relation relation(String catalog,
                                                             String schema,
                                                             String table,
                                                             String alias) {
        return new MysqlSqlCompletionRelationScope.Relation(catalog, schema, table, alias);
    }

    private MysqlSqlCompletionRelationScope.Relation localRelation(String table,
                                                                  String alias,
                                                                  List<String> columns) {
        return MysqlSqlCompletionRelationScope.Relation.local(table, alias, columns);
    }
}
