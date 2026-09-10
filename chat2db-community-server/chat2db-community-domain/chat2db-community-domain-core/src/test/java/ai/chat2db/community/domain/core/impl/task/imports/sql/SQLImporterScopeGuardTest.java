package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLImporterScopeGuardTest {

    @TempDir
    Path tempDirectory;

    @Test
    void acceptsMysqlTableTargetsOnlyWithinTheSelectedDatabaseAndTable() throws Exception {
        ExportedSqlStatementReader.Inspection matching = inspect("mysql-matching.sql",
                "INSERT INTO `app`.`demo_item` VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH, "MYSQL");
        ExportedSqlStatementReader.Inspection otherTable = inspect("mysql-other-table.sql",
                "REPLACE INTO `app`.`audit_item` VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH, "MYSQL");
        ImportTaskSpec spec = spec(ImportScope.TABLE, "app", null, "demo_item");

        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(spec, matching, "MYSQL"));
        assertOutsideScope(spec, otherTable, "MYSQL", ImportScope.TABLE);
    }

    @Test
    void constrainsPostgresqlDmlToTheSelectedSchema() throws Exception {
        ExportedSqlStatementReader.Inspection matching = inspect("postgres-matching.sql", """
                INSERT INTO public.first_item VALUES (1);
                UPDATE public.second_item SET value = 2 WHERE id = 1;
                """, ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL");
        ExportedSqlStatementReader.Inspection otherSchema = inspect("postgres-other-schema.sql",
                "DELETE FROM audit.first_item WHERE id = 1;\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL");
        ImportTaskSpec spec = spec(ImportScope.SCHEMA, "app", "public", null);

        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(spec, matching, "POSTGRESQL"));
        assertOutsideScope(spec, otherSchema, "POSTGRESQL", ImportScope.SCHEMA);
    }

    @Test
    void rejectsUnqualifiedPostgresqlTargetsInsteadOfTrustingTheSessionSearchPath() throws Exception {
        ExportedSqlStatementReader.Inspection unqualified = inspect("postgres-unqualified.sql",
                "INSERT INTO orders VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL");
        ExportedSqlStatementReader.Inspection qualified = inspect("postgres-qualified.sql",
                "INSERT INTO selected_schema.orders VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL");
        ImportTaskSpec spec = spec(ImportScope.SCHEMA, "app", "selected_schema", null);
        ImportTaskSpec databaseSpec = spec(ImportScope.DATABASE, "app", null, null);

        assertUnqualifiedTargetRejected(spec, unqualified, "POSTGRESQL");
        assertUnqualifiedTargetRejected(databaseSpec, unqualified, "POSTGRESQL");
        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(spec, qualified, "POSTGRESQL"));
        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(
                databaseSpec, qualified, "POSTGRESQL"));
    }

    @Test
    void constrainsSqlServerThreePartTargetsToTheSelectedDatabase() throws Exception {
        ExportedSqlStatementReader.Inspection matching = inspect("sqlserver-matching.sql",
                "MERGE INTO [app].[dbo].[demo_item] AS target USING [dbo].[source_item] AS source "
                        + "ON target.id = source.id WHEN MATCHED THEN UPDATE SET target.value = source.value;\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER");
        ExportedSqlStatementReader.Inspection otherDatabase = inspect("sqlserver-other-database.sql",
                "INSERT INTO [archive].[dbo].[demo_item] VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER");
        ImportTaskSpec spec = spec(ImportScope.DATABASE, "app", null, null);

        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(spec, matching, "SQLSERVER"));
        assertOutsideScope(spec, otherDatabase, "SQLSERVER", ImportScope.DATABASE);
    }

    @Test
    void rejectsUnqualifiedSqlServerTargetsInsteadOfTrustingTheSessionDefaultSchema() throws Exception {
        ExportedSqlStatementReader.Inspection unqualified = inspect("sqlserver-unqualified.sql",
                "INSERT INTO [orders] VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER");
        ExportedSqlStatementReader.Inspection qualified = inspect("sqlserver-qualified.sql",
                "INSERT INTO [selected_schema].[orders] VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER");
        ImportTaskSpec spec = spec(ImportScope.SCHEMA, "app", "selected_schema", null);

        assertUnqualifiedTargetRejected(spec, unqualified, "SQLSERVER");
        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(spec, qualified, "SQLSERVER"));
    }

    @Test
    void constrainsOracleDmlToTheSelectedSchema() throws Exception {
        ExportedSqlStatementReader.Inspection matching = inspect("oracle-matching.sql",
                "INSERT INTO APP.DEMO_ITEM VALUES (1);\n/\n",
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE");
        ExportedSqlStatementReader.Inspection otherSchema = inspect("oracle-other-schema.sql",
                "UPDATE AUDIT.DEMO_ITEM SET VALUE = 2 WHERE ID = 1;\n/\n",
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE");
        ImportTaskSpec spec = spec(ImportScope.SCHEMA, "APPDB", "APP", null);

        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(spec, matching, "ORACLE"));
        assertOutsideScope(spec, otherSchema, "ORACLE", ImportScope.SCHEMA);
    }

    @Test
    void rejectsUnqualifiedOracleTargetsInsteadOfTrustingTheSessionCurrentSchema() throws Exception {
        ExportedSqlStatementReader.Inspection unqualified = inspect("oracle-unqualified.sql",
                "INSERT INTO ORDERS VALUES (1);\n/\n",
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE");
        ExportedSqlStatementReader.Inspection qualified = inspect("oracle-qualified.sql",
                "INSERT INTO SELECTED_SCHEMA.ORDERS VALUES (1);\n/\n",
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE");
        ImportTaskSpec spec = spec(ImportScope.SCHEMA, "APPDB", "SELECTED_SCHEMA", null);

        assertUnqualifiedTargetRejected(spec, unqualified, "ORACLE");
        assertDoesNotThrow(() -> SQLImporter.requireTargetsWithinScope(spec, qualified, "ORACLE"));
    }

    @Test
    void failsClosedWhenTheScopeBoundaryIsMissing() throws Exception {
        ExportedSqlStatementReader.Inspection inspection = inspect("missing-target.sql",
                "INSERT INTO demo_item VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL");

        TaskExecutionException missingTarget = assertThrows(TaskExecutionException.class,
                () -> SQLImporter.requireTargetsWithinScope(new ImportTaskSpec(), inspection, "POSTGRESQL"));
        TaskExecutionException missingSchema = assertThrows(TaskExecutionException.class,
                () -> SQLImporter.requireTargetsWithinScope(
                        spec(ImportScope.SCHEMA, "app", null, null), inspection, "POSTGRESQL"));
        TaskExecutionException missingDatabase = assertThrows(TaskExecutionException.class,
                () -> SQLImporter.requireTargetsWithinScope(
                        spec(ImportScope.DATABASE, null, null, null), inspection, "POSTGRESQL"));

        assertTrue(missingTarget.getMessage().contains("target is required"));
        assertTrue(missingSchema.getMessage().contains("target schema"));
        assertTrue(missingDatabase.getMessage().contains("target database"));
    }

    private ExportedSqlStatementReader.Inspection inspect(String name, String sql,
            ExportedSqlStatementReader.ExporterProfile profile, String databaseType) throws Exception {
        Path source = Files.writeString(tempDirectory.resolve(name), sql, StandardCharsets.UTF_8);
        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.inspect(
                source.toFile(), StandardCharsets.UTF_8, profile, databaseType, null);
        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        return inspection;
    }

    private ImportTaskSpec spec(String scope, String database, String schema, String table) {
        return ImportTaskSpec.builder()
                .scope(scope)
                .target(TaskTargetSnapshot.builder()
                        .databaseName(database)
                        .schemaName(schema)
                        .tableName(table)
                        .build())
                .build();
    }

    private void assertOutsideScope(ImportTaskSpec spec, ExportedSqlStatementReader.Inspection inspection,
            String databaseType, String scope) {
        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> SQLImporter.requireTargetsWithinScope(spec, inspection, databaseType));
        assertTrue(failure.getMessage().contains("outside the selected " + scope));
    }

    private void assertUnqualifiedTargetRejected(ImportTaskSpec spec,
            ExportedSqlStatementReader.Inspection inspection, String databaseType) {
        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> SQLImporter.requireTargetsWithinScope(spec, inspection, databaseType));
        assertTrue(failure.getMessage().contains("is not schema-qualified"));
    }
}
