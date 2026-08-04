package ai.chat2db.plugin.postgresql;

import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PostgreSQLDBManagerTest {

    @Test
    void buildsDropDatabaseSqlWithStrictIdentifierEscaping() {
        TestPostgreSQLDBManager manage = new TestPostgreSQLDBManager();

        manage.dropDatabase(null, "a\"; DROP DATABASE b; --");

        assertEquals("DROP DATABASE \"a\"\"; DROP DATABASE b; --\"", manage.sql);
    }

    @Test
    void buildsDropSchemaSqlWithoutCascade() {
        TestPostgreSQLDBManager manage = new TestPostgreSQLDBManager();

        manage.dropSchema(null, "app_db", "tenant_schema");

        assertEquals("DROP SCHEMA \"tenant_schema\"", manage.sql);
        assertFalse(manage.sql.contains("CASCADE"));
    }

    @Test
    void buildsSchemaQualifiedTableStatementsWithoutDoubleQuotingServiceNames() throws Exception {
        PostgreSQLDBManager manager = new PostgreSQLDBManager();
        String source = PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways("ord\"ers");
        String target = PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways("ord\"ers_copy");

        assertEquals("DROP TABLE \"analytics\".\"ord\"\"ers\"",
                manager.dropTable(null, "ignored_database", "analytics", "ord\"ers"));
        assertEquals("TRUNCATE TABLE \"analytics\".\"ord\"\"ers\"",
                manager.truncateTable(null, "ignored_database", "analytics", source));
        assertEquals("CREATE TABLE \"analytics\".\"ord\"\"ers_copy\" AS TABLE "
                        + "\"analytics\".\"ord\"\"ers\" WITH DATA",
                PostgreSQLDBManager.buildCopyTableSql("analytics", source, target, true));
        assertEquals("CREATE TABLE \"ord\"\"ers_copy\" AS TABLE \"ord\"\"ers\" WITH NO DATA",
                PostgreSQLDBManager.buildCopyTableSql(null, source, target, false));
    }

    private static class TestPostgreSQLDBManager extends PostgreSQLDBManager {
        private String sql;

        @Override
        void executeDropSql(Connection connection, String sql) {
            this.sql = sql;
        }
    }
}
