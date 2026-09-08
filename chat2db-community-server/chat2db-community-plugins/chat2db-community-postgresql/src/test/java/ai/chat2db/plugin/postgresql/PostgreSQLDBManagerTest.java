package ai.chat2db.plugin.postgresql;

import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals("DROP TABLE \"analytics\".\"ord\"\"ers\"",
                manager.dropTable(null, "ignored_database", "analytics", "ord\"ers"));
        assertEquals("TRUNCATE TABLE \"analytics\".\"ord\"\"ers\"",
                manager.truncateTable(null, "ignored_database", "analytics", "ord\"ers"));
        assertEquals("CREATE TABLE \"analytics\".\"ord\"\"ers_copy\" AS TABLE "
                        + "\"analytics\".\"ord\"\"ers\" WITH DATA",
                PostgreSQLDBManager.buildCopyTableSql("analytics", "ord\"ers", "ord\"ers_copy", true));
        assertEquals("CREATE TABLE \"ord\"\"ers_copy\" AS TABLE \"ord\"\"ers\" WITH NO DATA",
                PostgreSQLDBManager.buildCopyTableSql(null, "ord\"ers", "ord\"ers_copy", false));
    }

    @Test
    void probesNativePostgresqlPrimaryResourcesAndSchemaTriggers() {
        ResourceProbePostgreSQLDBManager manager = new ResourceProbePostgreSQLDBManager();
        manager.maxConnections = 120;
        manager.activeConnections = 14;
        manager.triggerCount = 3;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "app", "tenant_a");

        assertTrue(snapshot.connectionCapacityKnown());
        assertEquals(120, snapshot.maxConnections());
        assertEquals(14, snapshot.activeConnections());
        assertTrue(snapshot.replicationStatusKnown());
        assertFalse(snapshot.replica());
        assertNull(snapshot.replicationLagSeconds());
        assertTrue(snapshot.triggerStatusKnown());
        assertEquals(3, snapshot.triggerCount());
        assertEquals("tenant_a", manager.probedSchema);
        assertFalse(snapshot.diskCapacityKnown());
    }

    @Test
    void reportsReplicaLagAndUnknownTriggerMetadataIndependently() {
        ResourceProbePostgreSQLDBManager manager = new ResourceProbePostgreSQLDBManager();
        manager.replica = true;
        manager.replicationLagSeconds = 41L;
        manager.failTriggerProbe = true;

        ImportResourceSnapshot snapshot = manager.probeImportResources(null, "app", "public");

        assertTrue(snapshot.replicationStatusKnown());
        assertTrue(snapshot.replica());
        assertEquals(41L, snapshot.replicationLagSeconds());
        assertFalse(snapshot.triggerStatusKnown());
        assertTrue(snapshot.evidence().contains("trigger metadata unavailable"));
    }

    @Test
    void keepsPostgresqlCatalogProbesDisabledForDerivativeManagers() {
        ImportResourceSnapshot snapshot = new TestPostgreSQLDBManager()
                .probeImportResources(null, "app", "public");

        assertFalse(snapshot.connectionCapacityKnown());
        assertFalse(snapshot.replicationStatusKnown());
        assertFalse(snapshot.triggerStatusKnown());
        assertTrue(snapshot.evidence().contains("derivative dialect managers"));
    }

    private static class TestPostgreSQLDBManager extends PostgreSQLDBManager {
        private String sql;

        @Override
        void executeDropSql(Connection connection, String sql) {
            this.sql = sql;
        }
    }

    private static class ResourceProbePostgreSQLDBManager extends PostgreSQLDBManager {
        private int maxConnections = 100;
        private int activeConnections = 5;
        private boolean replica;
        private Long replicationLagSeconds;
        private int triggerCount;
        private boolean failTriggerProbe;
        private String probedSchema;

        private ResourceProbePostgreSQLDBManager() {
            super(true);
        }

        @Override
        int queryInt(Connection connection, String sql) {
            return sql.contains("pg_settings") ? maxConnections : activeConnections;
        }

        @Override
        boolean queryBoolean(Connection connection, String sql) {
            return replica;
        }

        @Override
        Long queryNullableLong(Connection connection, String sql) {
            return replicationLagSeconds;
        }

        @Override
        int queryTriggerCount(Connection connection, String schemaName) throws SQLException {
            probedSchema = schemaName;
            if (failTriggerProbe) {
                throw new SQLException("fixture trigger failure");
            }
            return triggerCount;
        }
    }
}
