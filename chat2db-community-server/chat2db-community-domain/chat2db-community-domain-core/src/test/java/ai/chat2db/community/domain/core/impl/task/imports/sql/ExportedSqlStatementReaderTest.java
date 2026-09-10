package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportedSqlStatementReaderTest {

    @Test
    void resolvesEverySupportedExporterAndKeepsDefaultSqlModeUnset() {
        assertEquals(List.of(ExportedSqlStatementReader.ExporterProfile.values()), List.of(
                ExportedSqlStatementReader.ExporterProfile.resolve("NAVICAT"),
                ExportedSqlStatementReader.ExporterProfile.resolve("dbeaver"),
                ExportedSqlStatementReader.ExporterProfile.resolve("DataGrip"),
                ExportedSqlStatementReader.ExporterProfile.resolve("HeidiSQL"),
                ExportedSqlStatementReader.ExporterProfile.resolve("phpMyAdmin"),
                ExportedSqlStatementReader.ExporterProfile.resolve("mysql-workbench"),
                ExportedSqlStatementReader.ExporterProfile.resolve("pgAdmin"),
                ExportedSqlStatementReader.ExporterProfile.resolve("SSMS"),
                ExportedSqlStatementReader.ExporterProfile.resolve("Oracle SQL Developer")));
        assertEquals(ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH,
                ExportedSqlStatementReader.ExporterProfile.resolve("Workbench"));
        assertEquals(ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER,
                ExportedSqlStatementReader.ExporterProfile.resolve("SQL Developer"));
        assertNull(ExportedSqlStatementReader.ExporterProfile.resolve("  "));
        assertNull(SQLImporter.configuredExporterProfile(ImportTaskSpec.builder().build()));
        assertNull(SQLImporter.configuredExporterProfile(ImportTaskSpec.builder()
                .options(ImportOptions.builder().build()).build()));
        assertEquals(ExportedSqlStatementReader.ExporterProfile.NAVICAT,
                SQLImporter.configuredExporterProfile(ImportTaskSpec.builder()
                        .options(ImportOptions.builder().sqlExporterProfile("Navicat").build()).build()));
        assertThrows(IllegalArgumentException.class,
                () -> SQLImporter.configuredExporterProfile(ImportTaskSpec.builder()
                        .sourceKind("THIRD_PARTY").options(ImportOptions.builder().build()).build()));
        assertThrows(IllegalArgumentException.class,
                () -> ExportedSqlStatementReader.ExporterProfile.resolve("unknown-exporter"));
    }

    @Test
    void slicesNavicatFixtureAndFiltersOnlyDataStatements() throws Exception {
        File source = fixture("/import/sql-exporters/navicat-mysql-data-transfer.sql");

        ExportedSqlStatementReader.Inspection inspected = ExportedSqlStatementReader.inspect(
                source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT);
        List<String> dataStatements = new ArrayList<>();
        ExportedSqlStatementReader.Inspection streamed = ExportedSqlStatementReader.streamDataStatements(
                source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT,
                dataStatements::add, null);

        assertTrue(inspected.supported());
        assertEquals(inspected, streamed);
        assertEquals(5, inspected.dataStatementCount());
        assertEquals(9, inspected.filteredStatementCount());
        assertEquals(5, dataStatements.size());
        assertTrue(dataStatements.get(0).startsWith("INSERT INTO"));
        assertTrue(dataStatements.get(0).contains("semi;colon -- # /* literal */"));
        assertTrue(dataStatements.get(1).startsWith("REPLACE INTO"));
        assertTrue(dataStatements.get(2).startsWith("INSERT IGNORE"));
        assertTrue(dataStatements.get(3).startsWith("UPDATE"));
        assertTrue(dataStatements.get(4).startsWith("DELETE"));
        assertFalse(String.join("\n", dataStatements).contains("must-not-escape-procedure"));
    }

    @Test
    void handlesSsmsGoBatchesAndBlocksIdentityInsert(@TempDir Path directory) throws Exception {
        File supported = write(directory, "ssms.sql", """
                SET ANSI_NULLS ON
                GO
                CREATE TABLE [dbo].[item] ([id] int, [name] nvarchar(50));
                GO
                CREATE OR ALTER PROCEDURE [dbo].[seed_item] AS
                BEGIN
                  INSERT INTO [dbo].[item] VALUES (99, N'inside procedure');
                  UPDATE [dbo].[item] SET [name] = N'inside procedure';
                END
                GO
                INSERT INTO [dbo].[item] VALUES (1, N'GO; remains data');
                GO
                UPDATE [dbo].[item] SET [name] = N'changed' WHERE [id] = 1;
                GO
                """);
        List<String> statements = new ArrayList<>();
        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.streamDataStatements(
                supported, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.SSMS,
                statements::add, null);

        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("GO; remains data"));
        assertFalse(String.join("\n", statements).contains("inside procedure"));

        File identityInsert = write(directory, "identity.sql", """
                SET IDENTITY_INSERT [dbo].[item] ON
                GO
                INSERT INTO [dbo].[item] VALUES (1, N'one');
                GO
                """);
        ExportedSqlStatementReader.Inspection blocked = ExportedSqlStatementReader.inspect(
                identityInsert, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.SSMS);

        assertFalse(blocked.supported());
        assertEquals("IDENTITY_INSERT", blocked.unsupported().code());
        assertEquals(1, blocked.unsupported().line());
        assertEquals(0, blocked.dataStatementCount());
    }

    @Test
    void rejectsSsmsGoBatchesThatMixWrapperDdlAndDmlBeforeEmittingData(@TempDir Path directory)
            throws Exception {
        for (TestSql mixedBatch : List.of(
                new TestSql("ssms-create-insert.sql", """
                        CREATE TABLE [dbo].[item] ([id] int)
                        INSERT INTO [dbo].[item] VALUES (1)
                        GO
                        """, ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER"),
                new TestSql("ssms-drop-insert.sql", """
                        DROP TABLE [dbo].[item]
                        INSERT INTO [dbo].[item] VALUES (1)
                        GO
                        """, ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER"),
                new TestSql("ssms-mixed-before-normal-insert.sql", """
                        CREATE TABLE [dbo].[item] ([id] int)
                        INSERT INTO [dbo].[item] VALUES (1)
                        GO
                        INSERT INTO [dbo].[item] VALUES (2)
                        GO
                        """, ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER"))) {
            List<String> statements = new ArrayList<>();

            ExportedSqlStatementReader.Inspection inspection =
                    ExportedSqlStatementReader.streamDataStatements(
                            write(directory, mixedBatch.fileName(), mixedBatch.sql()),
                            StandardCharsets.UTF_8, mixedBatch.profile(), mixedBatch.databaseType(),
                            statements::add, null);

            assertFalse(inspection.supported(), mixedBatch.fileName());
            assertEquals("NON_DATA_STATEMENT", inspection.unsupported().code(), mixedBatch.fileName());
            assertEquals(0, inspection.dataStatementCount(), mixedBatch.fileName());
            assertEquals(0, inspection.filteredStatementCount(), mixedBatch.fileName());
            assertTrue(statements.isEmpty(), mixedBatch.fileName());
        }
    }

    @Test
    void respectsPostgresDollarQuotesAndEscapeStringRules(@TempDir Path directory) throws Exception {
        File source = write(directory, "postgres.sql", """
                CREATE FUNCTION public.touch_item() RETURNS trigger AS $body$
                BEGIN
                  PERFORM 'inside;function';
                  RETURN NEW;
                END;
                $body$ LANGUAGE plpgsql;
                INSERT INTO public.item VALUES (1, E'C:\\\\tmp\\\\');
                INSERT INTO public.item VALUES (2, 'C:\\');
                INSERT INTO public.foo$bar$baz VALUES (3, 'dollar identifier');
                """);
        List<String> statements = new ArrayList<>();

        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.streamDataStatements(
                source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.PGADMIN,
                statements::add, null);

        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        assertEquals(3, inspection.dataStatementCount());
        assertEquals(1, inspection.filteredStatementCount());
        assertEquals(3, statements.size());
        assertFalse(String.join("\n", statements).contains("inside;function"));
    }

    @Test
    void appliesTargetDialectToMultiDatabaseExporterProfiles(@TempDir Path directory) throws Exception {
        File source = write(directory, "datagrip-postgres.sql", """
                \\set ON_ERROR_STOP on
                CREATE FUNCTION public.one() RETURNS integer AS $$
                  SELECT 1;
                $$ LANGUAGE sql;
                INSERT INTO public.foo$bar$baz
                SELECT 1 AS
                GO
                ;
                """);
        List<String> statements = new ArrayList<>();

        ExportedSqlStatementReader.Inspection inspection =
                ExportedSqlStatementReader.streamDataStatements(source, StandardCharsets.UTF_8,
                        ExportedSqlStatementReader.ExporterProfile.DATAGRIP, "POSTGRESQL",
                        statements::add, null);

        assertTrue(inspection.supported());
        assertEquals(1, inspection.dataStatementCount());
        assertEquals(1, inspection.filteredStatementCount());
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).contains("foo$bar$baz"));
        assertTrue(statements.get(0).lines().map(String::trim).anyMatch("GO"::equals));

        File sqlServer = write(directory, "datagrip-sqlserver.sql", """
                SET ANSI_NULLS ON
                GO
                INSERT INTO [dbo].[item] VALUES (1, N'one');
                GO
                """);
        ExportedSqlStatementReader.Inspection sqlServerInspection =
                ExportedSqlStatementReader.streamDataStatements(sqlServer, StandardCharsets.UTF_8,
                        ExportedSqlStatementReader.ExporterProfile.DATAGRIP, "SQLSERVER",
                        ignored -> { }, null);
        assertEquals(1, sqlServerInspection.dataStatementCount());
        assertEquals(1, sqlServerInspection.filteredStatementCount());
    }

    @Test
    void appliesOracleSlashToMultiDatabaseExportersAndRejectsForeignDelimiter(@TempDir Path directory)
            throws Exception {
        File oracle = write(directory, "dbeaver-oracle.sql", """
                CREATE OR REPLACE PROCEDURE seed_demo AS
                BEGIN
                  INSERT INTO demo_item VALUES (99, q'[inside;procedure]');
                END;
                /
                INSERT INTO demo_item VALUES (1, q'[outside;value]');
                /
                """);
        List<String> statements = new ArrayList<>();
        ExportedSqlStatementReader.Inspection oracleInspection =
                ExportedSqlStatementReader.streamDataStatements(oracle, StandardCharsets.UTF_8,
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "ORACLE",
                        statements::add, null);

        assertTrue(oracleInspection.supported(), () -> String.valueOf(oracleInspection.unsupported()));
        assertEquals(1, oracleInspection.dataStatementCount());
        assertEquals(1, oracleInspection.filteredStatementCount());
        assertEquals(1, statements.size());
        assertFalse(statements.get(0).contains("inside;procedure"));

        File foreignDelimiter = write(directory, "postgres-delimiter.sql", """
                DELIMITER $$
                INSERT INTO demo_item VALUES (1)$$
                """);
        ExportedSqlStatementReader.Inspection blocked = ExportedSqlStatementReader.inspect(
                foreignDelimiter, StandardCharsets.UTF_8,
                ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL", null);
        assertFalse(blocked.supported());
        assertEquals("DELIMITER_DIRECTIVE", blocked.unsupported().code());
        assertEquals(1, blocked.unsupported().line());
    }

    @Test
    void convertsDefaultPgDumpTextCopyToValidatedInsertStatements(@TempDir Path directory) throws Exception {
        File source = write(directory, "pg-dump-copy.sql", """
                SET statement_timeout = 0;
                COPY public."odd table" ("id", note, payload) FROM stdin;
                1\tAlice\\tBob\t\\N
                2\tO'Brien\\\\path\tline\\nnext
                4\tUTF8\t\\xC3\\xA4-\\303\\244
                \\.
                INSERT INTO public."odd table" VALUES (3, 'after copy', NULL);
                """);

        ExportedSqlStatementReader.Inspection inspected = ExportedSqlStatementReader.inspect(
                source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
        List<String> statements = new ArrayList<>();
        ExportedSqlStatementReader.Inspection streamed = ExportedSqlStatementReader.streamDataStatements(
                source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.PGADMIN,
                statements::add, null);

        assertTrue(inspected.supported());
        assertEquals(inspected, streamed);
        assertEquals(6, inspected.statementCount());
        assertEquals(4, inspected.dataStatementCount());
        assertEquals(2, inspected.filteredStatementCount());
        assertEquals(4, statements.size());
        assertTrue(statements.get(0).startsWith(
                "INSERT INTO public.\"odd table\" (\"id\", note, payload) VALUES ("));
        assertTrue(statements.get(0).contains("E'Alice\\011Bob'"));
        assertTrue(statements.get(0).endsWith(", NULL)"));
        assertTrue(statements.get(1).contains("E'O''Brien\\\\path'"));
        assertTrue(statements.get(1).contains("E'line\\012next'"));
        assertTrue(statements.get(2).contains("E'\\xC3\\xA4-\\303\\244'"));
        assertTrue(statements.get(3).startsWith("INSERT INTO"));
    }

    @Test
    void rejectsInvalidPgDumpCopyBeforeItCanEmitData(@TempDir Path directory) throws Exception {
        ExportedSqlStatementReader.UnsupportedDirective wrongColumns = unsupported(directory,
                "copy-columns.sql", """
                        COPY public.item (id, name) FROM stdin;
                        1
                        \\.
                        """, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
        assertEquals("COPY_COLUMN_COUNT", wrongColumns.code());

        ExportedSqlStatementReader.UnsupportedDirective unterminated = unsupported(directory,
                "copy-unterminated.sql", """
                        COPY public.item (id) FROM stdin;
                        1
                        """, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
        assertEquals("COPY_UNTERMINATED", unterminated.code());

        ExportedSqlStatementReader.UnsupportedDirective invalidOctal = unsupported(directory,
                "copy-invalid-octal.sql", """
                        COPY public.item (payload) FROM stdin;
                        \\400
                        \\.
                        """, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
        assertEquals("COPY_ESCAPE", invalidOctal.code());

        ExportedSqlStatementReader.UnsupportedDirective missingColumns = unsupported(directory,
                "copy-missing-columns.sql", """
                        COPY public.item FROM stdin;
                        1
                        \\.
                        """, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
        assertEquals("COPY_COLUMNS", missingColumns.code());
    }

    @Test
    void handlesOracleSlashBlocksSqlclWrappersAndAlternativeQuotes(@TempDir Path directory) throws Exception {
        File source = write(directory, "oracle.sql", """
                SET DEFINE OFF
                REM generated by Oracle SQL Developer
                CREATE OR REPLACE EDITIONABLE TRIGGER demo_trigger
                BEFORE INSERT ON demo_item
                FOR EACH ROW
                BEGIN
                  INSERT INTO demo_audit VALUES (q'[inside;trigger]');
                END;
                /
                CREATE OR REPLACE AND COMPILE JAVA SOURCE NAMED "DemoSource" AS
                public class DemoSource {
                  public static final String VALUE = "inside;java";
                }
                /
                INSERT INTO demo_item VALUES (1, q'<outside;value>');
                /
                """);
        List<String> statements = new ArrayList<>();

        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.streamDataStatements(
                source, StandardCharsets.UTF_8,
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, statements::add, null);

        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        assertEquals(1, inspection.dataStatementCount());
        assertEquals(2, inspection.filteredStatementCount());
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).contains("q'<outside;value>'"));
        assertFalse(statements.get(0).contains("inside;trigger"));
    }

    @Test
    void reportsUnsupportedBulkAndExternalClientDirectives(@TempDir Path directory) throws Exception {
        ExportedSqlStatementReader.UnsupportedDirective copy = unsupported(directory, "copy-options.sql", """
                COPY public.item (id, name) FROM stdin WITH (FORMAT csv);
                """, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
        assertEquals("COPY_OPTIONS", copy.code());
        assertTrue(copy.recommendation().contains("--inserts"));

        assertEquals("LOAD_DATA", unsupported(directory, "load.sql",
                "LOAD DATA LOCAL INFILE 'items.csv' INTO TABLE item;",
                ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH).code());
        assertEquals("SQLSERVER_BULK", unsupported(directory, "bulk.sql",
                "BULK INSERT dbo.item FROM 'items.csv';",
                ExportedSqlStatementReader.ExporterProfile.SSMS).code());
        assertEquals("SQLSERVER_BULK", unsupported(directory, "openrowset.sql",
                "INSERT INTO dbo.item SELECT * FROM OPENROWSET(BULK 'items.csv', SINGLE_CLOB) AS rows;",
                ExportedSqlStatementReader.ExporterProfile.DATAGRIP).code());
        assertEquals("SQLSERVER_SECONDARY_TARGET", unsupported(directory, "output-into.sql",
                "INSERT INTO dbo.item OUTPUT inserted.id INTO dbo.audit(item_id) VALUES (1);\nGO\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS).code());
        assertEquals("GO_REPEAT", unsupported(directory, "go-repeat.sql", "SELECT 1\nGO 2147483648\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS).code());
        assertEquals("GO_DIRECTIVE", unsupported(directory, "go-invalid.sql", "SELECT 1\nGO -1\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS).code());
        assertEquals("PSQL_META_COMMAND", unsupported(directory, "psql.sql", "\\i included.sql\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("PSQL_COPY", unsupported(directory, "psql-copy.sql",
                "\\copy public.item from 'items.tsv'\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("SQLCL_EXTERNAL_COMMAND", unsupported(directory, "sqlcl.sql", "@included.sql\n",
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER).code());
    }

    @Test
    void handlesTypicalMainstreamManagerExports() throws Exception {
        assertSupportedFixture("/import/sql-exporters/dbeaver-postgresql-data-export.sql",
                ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL", 1,
                new ExportedSqlStatementReader.TargetTable(null, "public", "demo_item"));
        assertSupportedFixture("/import/sql-exporters/datagrip-postgresql-data-extractor.sql",
                ExportedSqlStatementReader.ExporterProfile.DATAGRIP, "POSTGRESQL", 2,
                new ExportedSqlStatementReader.TargetTable(null, "public", "demo_item"));
        assertSupportedFixture("/import/sql-exporters/heidisql-mariadb-export.sql",
                ExportedSqlStatementReader.ExporterProfile.HEIDISQL, "MARIADB", 1,
                new ExportedSqlStatementReader.TargetTable(null, "app", "demo_item"));
        assertSupportedFixture("/import/sql-exporters/pgadmin-pgdump-data-export.sql",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL", 2,
                new ExportedSqlStatementReader.TargetTable(null, "public", "demo_item"));
        assertSupportedFixture("/import/sql-exporters/oracle-sql-developer-data-export.sql",
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE", 2,
                new ExportedSqlStatementReader.TargetTable(null, "APP", "DEMO_ITEM"));
        assertSupportedFixture("/import/sql-exporters/phpmyadmin-mysql-data-only.sql",
                ExportedSqlStatementReader.ExporterProfile.PHPMYADMIN, "MYSQL", 1,
                new ExportedSqlStatementReader.TargetTable(null, null, "demo_item"));

        ExportedSqlStatementReader.Inspection workbench = ExportedSqlStatementReader.inspect(
                fixture("/import/sql-exporters/mysql-workbench-data-export.sql"),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH);
        assertTrue(workbench.supported(), () -> String.valueOf(workbench.unsupported()));
        assertEquals(1, workbench.dataStatementCount());
        assertEquals(List.of(new ExportedSqlStatementReader.TargetTable(null, "app", "demo_item")),
                workbench.targetTables());

        ExportedSqlStatementReader.Inspection phpMyAdmin = ExportedSqlStatementReader.inspect(
                fixture("/import/sql-exporters/phpmyadmin-mysql-export.sql"),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.PHPMYADMIN);
        assertFalse(phpMyAdmin.supported());
        assertEquals("SESSION_DIRECTIVE", phpMyAdmin.unsupported().code());
        assertEquals(0, phpMyAdmin.dataStatementCount());

        ExportedSqlStatementReader.Inspection ssms = ExportedSqlStatementReader.inspect(
                fixture("/import/sql-exporters/ssms-data-export.sql"),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.SSMS);
        assertTrue(ssms.supported(), () -> String.valueOf(ssms.unsupported()));
        assertEquals(2, ssms.dataStatementCount());
        assertEquals(List.of(new ExportedSqlStatementReader.TargetTable(null, "dbo", "demo_item")),
                ssms.targetTables());
    }

    @Test
    void canonicalizesQuotedAndUnquotedIdentifiersByDialect(@TempDir Path directory) throws Exception {
        ExportedSqlStatementReader.Inspection postgres = ExportedSqlStatementReader.inspect(
                write(directory, "postgres-identifier-case.sql", """
                        INSERT INTO App_Schema.Target_Rows VALUES (1);
                        INSERT INTO "App_Schema"."Target_Rows" VALUES (2);
                        """),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.DBEAVER,
                "POSTGRESQL", null);
        assertTrue(postgres.supported(), () -> String.valueOf(postgres.unsupported()));
        assertEquals(List.of(
                new ExportedSqlStatementReader.TargetTable(null, "app_schema", "target_rows"),
                new ExportedSqlStatementReader.TargetTable(null, "App_Schema", "Target_Rows")),
                postgres.targetTables());

        ExportedSqlStatementReader.Inspection oracle = ExportedSqlStatementReader.inspect(
                write(directory, "oracle-identifier-case.sql", """
                        INSERT INTO App_Schema.Target_Rows VALUES (1);
                        INSERT INTO "App_Schema"."Target_Rows" VALUES (2);
                        """),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.DATAGRIP,
                "ORACLE", null);
        assertTrue(oracle.supported(), () -> String.valueOf(oracle.unsupported()));
        assertEquals(List.of(
                new ExportedSqlStatementReader.TargetTable(null, "APP_SCHEMA", "TARGET_ROWS"),
                new ExportedSqlStatementReader.TargetTable(null, "App_Schema", "Target_Rows")),
                oracle.targetTables());

        ExportedSqlStatementReader.Inspection sqlServer = ExportedSqlStatementReader.inspect(
                write(directory, "sqlserver-identifier-case.sql", """
                        INSERT INTO AppDb.App_Schema.Target_Rows VALUES (1);
                        GO
                        INSERT INTO [AppDb].[App_Schema].[Target_Rows] VALUES (2);
                        GO
                        """),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.SSMS,
                "SQLSERVER", null);
        assertTrue(sqlServer.supported(), () -> String.valueOf(sqlServer.unsupported()));
        assertEquals(List.of(new ExportedSqlStatementReader.TargetTable(
                "AppDb", "App_Schema", "Target_Rows")), sqlServer.targetTables());
    }

    private static void assertSupportedFixture(String resource,
            ExportedSqlStatementReader.ExporterProfile profile, String databaseType,
            int expectedDataStatements, ExportedSqlStatementReader.TargetTable expectedTarget)
            throws Exception {
        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.inspect(
                fixture(resource), StandardCharsets.UTF_8, profile, databaseType, null);
        assertTrue(inspection.supported(), () -> resource + ": " + inspection.unsupported());
        assertEquals(expectedDataStatements, inspection.dataStatementCount(), resource);
        assertEquals(List.of(expectedTarget), inspection.targetTables(), resource);
    }

    @Test
    void filtersKnownMysqlWrappersButRejectsSemanticExecutableComments(@TempDir Path directory)
            throws Exception {
        File safe = write(directory, "workbench-safe.sql", """
                /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
                /*!40101 SET NAMES utf8mb4 */;
                /*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
                /*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
                INSERT INTO app.item VALUES (1, 'safe');
                /*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
                /*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
                """);
        ExportedSqlStatementReader.Inspection safeInspection = ExportedSqlStatementReader.inspect(
                safe, StandardCharsets.UTF_8,
                ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH);
        assertTrue(safeInspection.supported(), () -> String.valueOf(safeInspection.unsupported()));
        assertEquals(1, safeInspection.dataStatementCount());

        assertEquals("MYSQL_EXECUTABLE_COMMENT", unsupported(directory, "workbench-time-zone.sql",
                "/*!40103 SET TIME_ZONE='+00:00' */;\nINSERT INTO app.item VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH).code());
        assertEquals("MYSQL_EXECUTABLE_COMMENT", unsupported(directory, "workbench-log-bin.sql",
                "/*!32316 SET @OLD_SQL_LOG_BIN=@@SQL_LOG_BIN, SQL_LOG_BIN=0 */;\n",
                ExportedSqlStatementReader.ExporterProfile.MYSQL_WORKBENCH).code());
    }

    @Test
    void rejectsSemanticSessionSettingsAndPostgresSideEffects(@TempDir Path directory) throws Exception {
        assertEquals("SESSION_DIRECTIVE", unsupported(directory, "mysql-time-zone.sql",
                "SET time_zone = '+00:00';\nINSERT INTO item VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.NAVICAT).code());
        assertEquals("SESSION_DIRECTIVE", unsupported(directory, "mysql-sql-mode.sql",
                "SET sql_mode = 'NO_ZERO_DATE';\n",
                ExportedSqlStatementReader.ExporterProfile.PHPMYADMIN).code());
        assertEquals("SESSION_DIRECTIVE", unsupported(directory, "postgres-strings.sql",
                "SET standard_conforming_strings = off;\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("SESSION_DIRECTIVE", unsupported(directory, "postgres-client-encoding-expression.sql",
                "SET client_encoding = 'UTF8' || current_user;\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("SESSION_DIRECTIVE", unsupported(directory, "ssms-dateformat.sql",
                "SET DATEFORMAT dmy\nGO\n",
                ExportedSqlStatementReader.ExporterProfile.SSMS).code());
        assertEquals("SESSION_DIRECTIVE", unsupported(directory, "oracle-nls.sql",
                "ALTER SESSION SET NLS_DATE_FORMAT='YYYY-MM-DD';\n",
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER).code());
        assertEquals("POSTGRES_SEQUENCE_SETVAL", unsupported(directory, "postgres-setval.sql",
                "SELECT pg_catalog.setval('public.item_id_seq', 42, true);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("POSTGRES_SEQUENCE_SETVAL", unsupported(directory, "postgres-nested-setval.sql",
                "INSERT INTO public.item SELECT pg_catalog.setval('public.item_id_seq', 42, true);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("POSTGRES_SEQUENCE_SETVAL", unsupported(directory, "postgres-quoted-setval.sql",
                "INSERT INTO public.item SELECT \"pg_catalog\".\"setval\"('public.item_id_seq', 42, true);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("POSTGRES_LARGE_OBJECT", unsupported(directory, "postgres-large-object.sql",
                "SELECT pg_catalog.lo_create(123);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("POSTGRES_LARGE_OBJECT", unsupported(directory, "postgres-large-object-unlink.sql",
                "DELETE FROM public.item WHERE id = pg_catalog.lo_unlink(123);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("POSTGRES_LARGE_OBJECT", unsupported(directory, "postgres-quoted-lo-unlink.sql",
                "DELETE FROM public.item WHERE id = \"pg_catalog\".\"lo_unlink\"(123);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
        assertEquals("NON_DATA_STATEMENT", unsupported(directory, "unknown-select.sql",
                "SELECT do_something();\n",
                ExportedSqlStatementReader.ExporterProfile.DBEAVER).code());
    }

    @Test
    void scansCompleteSyntaxBeyondThePositionalLexicalBuffer(@TempDir Path directory) throws Exception {
        String payload = "x".repeat(1024 * 1024 + 1024);
        File safe = write(directory, "postgres-large-literal.sql",
                "INSERT INTO public.item VALUES (1, '" + payload + "');\n");
        List<String> statements = new ArrayList<>();

        ExportedSqlStatementReader.Inspection safeInspection =
                ExportedSqlStatementReader.streamDataStatements(safe, StandardCharsets.UTF_8,
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, statements::add, null);

        assertTrue(safeInspection.supported(), () -> String.valueOf(safeInspection.unsupported()));
        assertEquals(1, statements.size());
        assertEquals("POSTGRES_SEQUENCE_SETVAL", unsupported(directory, "postgres-late-setval.sql",
                "INSERT INTO public.item SELECT '" + payload
                        + "' || pg_catalog.setval('public.item_id_seq', 42, true);\n",
                ExportedSqlStatementReader.ExporterProfile.PGADMIN).code());
    }

    @Test
    void rejectsUnparseableDmlAfterAValidTargetPrefix(@TempDir Path directory) throws Exception {
        File malformed = write(directory, "malformed-dml.sql",
                "INSERT INTO public.item VALUES (1) THIS IS NOT SQL;\n");
        List<String> statements = new ArrayList<>();

        ExportedSqlStatementReader.Inspection inspection =
                ExportedSqlStatementReader.streamDataStatements(malformed, StandardCharsets.UTF_8,
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, statements::add, null);

        assertFalse(inspection.supported());
        assertEquals("DML_TARGET_UNVERIFIED", inspection.unsupported().code());
        assertTrue(statements.isEmpty());
    }

    @Test
    void rejectsSqlServerLinkedServerTargetsWithoutTruncatingThem(@TempDir Path directory) throws Exception {
        for (String target : List.of(
                "LinkedSrv.AppDb.App_Schema.Target_Rows",
                "[LinkedSrv].[AppDb].[App_Schema].[Target_Rows]")) {
            File source = write(directory, "sqlserver-linked-" + Math.abs(target.hashCode()) + ".sql",
                    "INSERT INTO " + target + " (id) VALUES (1);\nGO\n");
            List<String> statements = new ArrayList<>();

            ExportedSqlStatementReader.Inspection inspection =
                    ExportedSqlStatementReader.streamDataStatements(source, StandardCharsets.UTF_8,
                            ExportedSqlStatementReader.ExporterProfile.SSMS,
                            "SQLSERVER", statements::add, null);

            assertFalse(inspection.supported(), target);
            assertEquals("DML_TARGET_UNVERIFIED", inspection.unsupported().code(), target);
            assertEquals(0, inspection.dataStatementCount(), target);
            assertTrue(statements.isEmpty(), target);
        }
    }

    @Test
    void rejectsDestructiveDdlAndNonWrapperTransactionControl(@TempDir Path directory) throws Exception {
        assertEquals("DATA_DESTRUCTIVE_DDL", unsupported(directory, "truncate.sql",
                "TRUNCATE TABLE app.item;\nINSERT INTO app.item VALUES (1);\n",
                ExportedSqlStatementReader.ExporterProfile.NAVICAT).code());
        for (String statement : List.of("ROLLBACK", "ROLLBACK TO before_item", "SAVEPOINT before_item",
                "RELEASE SAVEPOINT before_item", "COMMIT AND CHAIN", "START TRANSACTION READ ONLY")) {
            assertEquals("TRANSACTION_CONTROL", unsupported(directory,
                    "transaction-" + Math.abs(statement.hashCode()) + ".sql", statement + ";\n",
                    ExportedSqlStatementReader.ExporterProfile.NAVICAT).code());
        }

        File plainWrapper = write(directory, "plain-transaction-wrapper.sql", """
                START TRANSACTION;
                INSERT INTO app.item VALUES (1);
                COMMIT WORK;
                """);
        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.inspect(
                plainWrapper, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT);
        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        assertEquals(1, inspection.dataStatementCount());
        assertEquals(2, inspection.filteredStatementCount());
    }

    @Test
    void handlesPsqlSqlclAndSqlcmdLineCommandsWithoutSwallowingFollowingDml(@TempDir Path directory)
            throws Exception {
        File psql = write(directory, "psql-safe.sql", """
                \\set ON_ERROR_STOP on
                \\echo importing
                \\restrict token
                INSERT INTO public.item VALUES (1);
                \\unrestrict token
                """);
        ExportedSqlStatementReader.Inspection psqlInspection = ExportedSqlStatementReader.inspect(
                psql, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
        assertTrue(psqlInspection.supported(), () -> String.valueOf(psqlInspection.unsupported()));
        assertEquals(1, psqlInspection.dataStatementCount());

        for (String command : List.of("\\connect other", "\\encoding LATIN1", "\\set custom value")) {
            ExportedSqlStatementReader.Inspection blocked = ExportedSqlStatementReader.inspect(
                    write(directory, "psql-" + Math.abs(command.hashCode()) + ".sql",
                            command + "\nINSERT INTO public.item VALUES (1);\n"),
                    StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.PGADMIN);
            assertEquals("PSQL_META_COMMAND", blocked.unsupported().code());
            assertEquals(0, blocked.dataStatementCount());
        }

        for (String command : List.of("CONNECT user/password@db", "VARIABLE id NUMBER", "EXEC seed_demo")) {
            ExportedSqlStatementReader.Inspection blocked = ExportedSqlStatementReader.inspect(
                    write(directory, "sqlcl-" + Math.abs(command.hashCode()) + ".sql",
                            command + "\nINSERT INTO demo_item VALUES (1);\n/\n"),
                    StandardCharsets.UTF_8,
                    ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER);
            assertEquals("SQLCL_EXTERNAL_COMMAND", blocked.unsupported().code());
            assertEquals(0, blocked.dataStatementCount());
        }

        ExportedSqlStatementReader.Inspection unknownSqlclSet = ExportedSqlStatementReader.inspect(
                write(directory, "sqlcl-unknown-set.sql",
                        "SET HEADING OFF\nINSERT INTO demo_item VALUES (1);\n/\n"),
                StandardCharsets.UTF_8,
                ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER);
        assertEquals("SQLCL_COMMAND_UNSUPPORTED", unknownSqlclSet.unsupported().code());
        assertEquals(0, unknownSqlclSet.dataStatementCount());

        for (String command : List.of(":setvar DatabaseName app", "!! dir")) {
            ExportedSqlStatementReader.Inspection blocked = ExportedSqlStatementReader.inspect(
                    write(directory, "sqlcmd-" + Math.abs(command.hashCode()) + ".sql",
                            command + "\nINSERT INTO [dbo].[item] VALUES (1);\nGO\n"),
                    StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.SSMS);
            assertEquals("SQLCMD_COMMAND", blocked.unsupported().code());
            assertEquals(0, blocked.dataStatementCount());
        }
    }

    @Test
    void mapsCompatibleDatabaseFamiliesAndReportsEveryVerifiedTarget(@TempDir Path directory) throws Exception {
        File mysqlFamily = write(directory, "dbeaver-mariadb.sql", """
                INSERT INTO `app`.`first_item` VALUES (1);
                UPDATE `app`.`second_item` SET value = 2 WHERE id = 1;
                """);
        ExportedSqlStatementReader.Inspection maria = ExportedSqlStatementReader.inspect(
                mysqlFamily, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.DBEAVER,
                "MARIADB", null);
        assertTrue(maria.supported(), () -> String.valueOf(maria.unsupported()));
        assertEquals(List.of(
                        new ExportedSqlStatementReader.TargetTable(null, "app", "first_item"),
                        new ExportedSqlStatementReader.TargetTable(null, "app", "second_item")),
                maria.targetTables());

        ExportedSqlStatementReader.Inspection kingbase = ExportedSqlStatementReader.inspect(
                write(directory, "datagrip-kingbase.sql", "INSERT INTO public.item VALUES (1);\n"),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.DATAGRIP,
                "KINGBASE", null);
        assertTrue(kingbase.supported(), () -> String.valueOf(kingbase.unsupported()));

        ExportedSqlStatementReader.Inspection dm = ExportedSqlStatementReader.inspect(
                write(directory, "dbeaver-dm.sql", "INSERT INTO demo_item VALUES (q'[value;one]');\n/\n"),
                StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.DBEAVER,
                "DM", null);
        assertTrue(dm.supported(), () -> String.valueOf(dm.unsupported()));
    }

    @Test
    void rejectsMalformedInputInsteadOfReplacingBytes(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("malformed-utf8.sql");
        byte[] prefix = "INSERT INTO item VALUES ('".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "');\n".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, malformed, 0, prefix.length);
        malformed[prefix.length] = (byte) 0xC3;
        malformed[prefix.length + 1] = (byte) 0x28;
        System.arraycopy(suffix, 0, malformed, prefix.length + 2, suffix.length);
        Files.write(source, malformed);

        assertThrows(java.io.IOException.class, () -> ExportedSqlStatementReader.inspect(
                source.toFile(), StandardCharsets.UTF_8,
                ExportedSqlStatementReader.ExporterProfile.NAVICAT));
    }

    @Test
    void rejectsUnterminatedLexicalSections(@TempDir Path directory) throws Exception {
        File quote = write(directory, "quote.sql", "INSERT INTO item VALUES ('unterminated;\n");
        File comment = write(directory, "comment.sql", "INSERT INTO item VALUES (1); /* unterminated\n");

        assertThrows(IllegalArgumentException.class, () -> ExportedSqlStatementReader.inspect(
                quote, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT));
        assertThrows(IllegalArgumentException.class, () -> ExportedSqlStatementReader.inspect(
                comment, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT));
    }

    @Test
    void hashesTheEntireSourceSoEqualLengthScriptsWithEqualCountsStillDiffer(@TempDir Path directory)
            throws Exception {
        File first = write(directory, "first.sql", "INSERT INTO item VALUES (1);\n");
        File second = write(directory, "second.sql", "INSERT INTO item VALUES (2);\n");

        ExportedSqlStatementReader.Inspection firstInspection = ExportedSqlStatementReader.inspect(
                first, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT);
        ExportedSqlStatementReader.Inspection secondInspection = ExportedSqlStatementReader.inspect(
                second, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT);

        assertEquals(Files.size(first.toPath()), Files.size(second.toPath()));
        assertEquals(firstInspection.statementCount(), secondInspection.statementCount());
        assertEquals(firstInspection.dataStatementCount(), secondInspection.dataStatementCount());
        assertEquals(64, firstInspection.sourceSha256().length());
        assertFalse(firstInspection.sourceSha256().equals(secondInspection.sourceSha256()));
    }

    @Test
    void thirdPartyPolicyAcceptsOnlyLiteralInsertsAndPostgresDoNothing(@TempDir Path directory) throws Exception {
        ExportedSqlStatementReader.Inspection mysql = strictInspection(directory, "mysql-literals.sql", """
                INSERT INTO app.item (id, name) VALUES (1, 'one'), (2, NULL);
                """, ExportedSqlStatementReader.ExporterProfile.DBEAVER, "MYSQL");
        assertTrue(mysql.supported(), () -> String.valueOf(mysql.unsupported()));
        assertEquals(1, mysql.dataStatementCount());

        ExportedSqlStatementReader.Inspection postgres = strictInspection(directory, "postgres-upsert.sql", """
                INSERT INTO public.item (id, name) VALUES (0, 'plain');
                INSERT INTO public.item (id, name) VALUES (1, 'one') ON CONFLICT (id) DO NOTHING;
                """, ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL");
        assertTrue(postgres.supported(), () -> String.valueOf(postgres.unsupported()));
        assertEquals(2, postgres.dataStatementCount());
    }

    @Test
    void thirdPartyPolicyRequiresSinglePartUniqueColumnsAndExactValuesArity(@TempDir Path directory)
            throws Exception {
        List<TestSql> invalid = List.of(
                new TestSql("missing-columns.sql", "INSERT INTO app.item VALUES (1, 'one');",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL"),
                new TestSql("qualified-column.sql", "INSERT INTO app.item (item.id) VALUES (1);",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL"),
                new TestSql("duplicate-columns.sql", "INSERT INTO app.item (Item_Id, item_id) VALUES (1, 2);",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL"),
                new TestSql("missing-value.sql", "INSERT INTO app.item (id, name) VALUES (1);",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL"),
                new TestSql("extra-value.sql", "INSERT INTO app.item (id, name) VALUES (1, 'one', 3);",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL"),
                new TestSql("later-row-short.sql",
                        "INSERT INTO app.item (id, name) VALUES (1, 'one'), (2);",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL"),
                new TestSql("later-row-wide.sql",
                        "INSERT INTO app.item (id, name) VALUES (1, 'one'), (2, 'two', 3);",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL"));

        for (TestSql test : invalid) {
            ExportedSqlStatementReader.Inspection inspection = strictInspection(directory, test.fileName(),
                    test.sql(), test.profile(), test.databaseType());
            assertFalse(inspection.supported(), test.fileName());
            assertEquals("THIRD_PARTY_DML_UNSAFE", inspection.unsupported().code(), test.fileName());
            assertEquals(0, inspection.dataStatementCount(), test.fileName());
        }
    }

    @Test
    void thirdPartyPolicyRejectsMysqlAndSqlServerCaseInsensitiveDuplicateColumns(@TempDir Path directory)
            throws Exception {
        List<TestSql> duplicates = List.of(
                new TestSql("mysql-case-duplicate.sql", "INSERT INTO app.item (id, ID) VALUES (1, 2);",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("sqlserver-case-duplicate.sql",
                        "INSERT INTO [dbo].[item] (id, ID) VALUES (1, 2);\nGO\n",
                        ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER"));

        for (TestSql duplicate : duplicates) {
            ExportedSqlStatementReader.Inspection inspection = strictInspection(directory, duplicate.fileName(),
                    duplicate.sql(), duplicate.profile(), duplicate.databaseType());
            assertFalse(inspection.supported(), duplicate.fileName());
            assertEquals("THIRD_PARTY_DML_UNSAFE", inspection.unsupported().code(), duplicate.fileName());
            assertEquals(0, inspection.dataStatementCount(), duplicate.fileName());
        }
    }

    @Test
    void preservesWhitespaceInsideQuotedTableAndColumnIdentifiers(@TempDir Path directory) throws Exception {
        ExportedSqlStatementReader.Inspection inspection = strictInspection(directory,
                "quoted-space-identifiers.sql", """
                        INSERT INTO \" APP_SCHEMA \".\" TARGET_ROWS \" (\" ID \", \" NAME \")
                        VALUES (1, 'one');
                        """, ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL");

        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        assertEquals(1, inspection.targetTables().size());
        ExportedSqlStatementReader.TargetTable target = inspection.targetTables().get(0);
        assertEquals(" APP_SCHEMA ", target.schema());
        assertEquals(" TARGET_ROWS ", target.table());
        assertEquals(Set.of(
                        new ExportedSqlStatementReader.ColumnReference(" ID ", true),
                        new ExportedSqlStatementReader.ColumnReference(" NAME ", true)),
                target.columns());
    }

    @Test
    void thirdPartyPolicyAllowsWholeColumnReorderingButRejectsAChangedSet(@TempDir Path directory)
            throws Exception {
        ExportedSqlStatementReader.Inspection reordered = strictInspection(directory, "reordered-columns.sql", """
                INSERT INTO app.item (id, name) VALUES (1, 'one');
                INSERT INTO app.item (\"name\", \"id\") VALUES ('two', 2);
                """, ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL");

        assertTrue(reordered.supported(), () -> String.valueOf(reordered.unsupported()));
        assertEquals(2, reordered.dataStatementCount());
        assertEquals(1, reordered.targetTables().size());
        assertEquals(Set.of(
                        new ExportedSqlStatementReader.ColumnReference("id", false),
                        new ExportedSqlStatementReader.ColumnReference("name", false)),
                reordered.targetTables().get(0).columns());
        assertThrows(UnsupportedOperationException.class, () -> reordered.targetTables().get(0).columns().add(
                new ExportedSqlStatementReader.ColumnReference("extra", false)));

        ExportedSqlStatementReader.Inspection changed = strictInspection(directory, "changed-columns.sql", """
                INSERT INTO app.item (id, name) VALUES (1, 'one');
                INSERT INTO app.item (id, email) VALUES (2, 'two@example.test');
                """, ExportedSqlStatementReader.ExporterProfile.DBEAVER, "POSTGRESQL");

        assertFalse(changed.supported());
        assertEquals("THIRD_PARTY_DML_UNSAFE", changed.unsupported().code());
        assertEquals(1, changed.dataStatementCount());
        assertEquals(1, changed.targetTables().size());
    }

    @Test
    void thirdPartyPolicyCanonicalizesPostgresAndOracleColumnReferences(@TempDir Path directory)
            throws Exception {
        ExportedSqlStatementReader.Inspection postgres = strictInspection(directory, "postgres-columns.sql", """
                INSERT INTO public.item (MixedCase, "ExactCase") VALUES (1, 'one');
                INSERT INTO public.item ("ExactCase", MIXEDCASE) VALUES ('two', 2);
                """, ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL");
        assertTrue(postgres.supported(), () -> String.valueOf(postgres.unsupported()));
        assertEquals(Set.of(
                        new ExportedSqlStatementReader.ColumnReference("mixedcase", false),
                        new ExportedSqlStatementReader.ColumnReference("ExactCase", true)),
                postgres.targetTables().get(0).columns());

        ExportedSqlStatementReader.Inspection oracle = strictInspection(directory, "oracle-columns.sql", """
                INSERT INTO APP.ITEM (MixedCase, "ExactCase") VALUES (1, 'one');
                INSERT INTO APP.ITEM ("ExactCase", MIXEDCASE) VALUES ('two', 2);
                /
                """, ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE");
        assertTrue(oracle.supported(), () -> String.valueOf(oracle.unsupported()));
        assertEquals(Set.of(
                        new ExportedSqlStatementReader.ColumnReference("MIXEDCASE", false),
                        new ExportedSqlStatementReader.ColumnReference("ExactCase", true)),
                oracle.targetTables().get(0).columns());
    }

    @Test
    void thirdPartyPolicyRejectsQuotedColumnsThatCollideAfterDialectNormalization(@TempDir Path directory)
            throws Exception {
        List<TestSql> collisions = List.of(
                new TestSql("postgres-quoted-collision.sql",
                        "INSERT INTO public.item (item_id, \"item_id\") VALUES (1, 2);",
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL"),
                new TestSql("oracle-quoted-collision.sql",
                        "INSERT INTO APP.ITEM (item_id, \"ITEM_ID\") VALUES (1, 2);\n/\n",
                        ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE"));

        for (TestSql collision : collisions) {
            ExportedSqlStatementReader.Inspection inspection = strictInspection(directory, collision.fileName(),
                    collision.sql(), collision.profile(), collision.databaseType());
            assertFalse(inspection.supported(), collision.fileName());
            assertEquals("THIRD_PARTY_DML_UNSAFE", inspection.unsupported().code(), collision.fileName());
        }
    }

    @Test
    void thirdPartyPolicyCarriesCopyColumnsIntoEveryGeneratedInsert(@TempDir Path directory) throws Exception {
        File source = write(directory, "strict-copy.sql", """
                COPY public.item (Item_Id, "ExactName") FROM stdin;
                1	one
                2	two
                \\.
                """);
        List<String> statements = new ArrayList<>();
        List<ExportedSqlStatementReader.TargetTable> occurrences = new ArrayList<>();

        ExportedSqlStatementReader.Inspection inspection =
                ExportedSqlStatementReader.streamVerifiedDataStatements(
                        source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.PGADMIN,
                        "POSTGRESQL", ExportedSqlStatementReader.StatementPolicy.THIRD_PARTY_LITERAL_VALUES,
                        (statement, target) -> {
                            statements.add(statement);
                            occurrences.add(target);
                        }, null);

        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        assertEquals(2, inspection.dataStatementCount());
        assertEquals(1, inspection.filteredStatementCount());
        assertEquals(2, statements.size());
        assertEquals(2, occurrences.size());
        assertTrue(statements.stream().allMatch(statement -> statement.startsWith(
                "INSERT INTO public.item (Item_Id, \"ExactName\") VALUES (")));
        assertTrue(occurrences.stream().allMatch(inspection.targetTables().get(0)::equals));
        assertEquals(Set.of(
                        new ExportedSqlStatementReader.ColumnReference("item_id", false),
                        new ExportedSqlStatementReader.ColumnReference("ExactName", true)),
                inspection.targetTables().get(0).columns());
    }

    @Test
    void thirdPartyPolicyRejectsQueriesFunctionsSequencesDefaultsAndComputedValues(@TempDir Path directory)
            throws Exception {
        List<TestSql> malicious = List.of(
                new TestSql("mysql-select.sql", "INSERT INTO app.item (id) SELECT id FROM app.source;",
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "MYSQL"),
                new TestSql("mysql-function.sql", "INSERT INTO app.item (id) VALUES (UUID());",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("mysql-default.sql", "INSERT INTO app.item (id) VALUES (DEFAULT);",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("mysql-set.sql", "INSERT INTO app.item SET id = 1;",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("mysql-replace.sql", "REPLACE INTO app.item VALUES (1, 'fixed');",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("mysql-upsert.sql", "UPSERT INTO app.item VALUES (1, 'fixed');",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("mysql-upsert-fixed.sql",
                        "INSERT INTO app.item (id, name) VALUES (1, 'one') ON DUPLICATE KEY UPDATE name = 'fixed';",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("mysql-upsert-expression.sql",
                        "INSERT INTO app.item (id) VALUES (1) ON DUPLICATE KEY UPDATE id = VALUES(id);",
                        ExportedSqlStatementReader.ExporterProfile.NAVICAT, "MYSQL"),
                new TestSql("postgres-sequence.sql", "INSERT INTO public.item (id) VALUES (nextval('item_seq'));",
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL"),
                new TestSql("postgres-subquery.sql", "INSERT INTO public.item (id) VALUES ((SELECT 1));",
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL"),
                new TestSql("postgres-returning.sql", "INSERT INTO public.item (id) VALUES (1) RETURNING id;",
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL"),
                new TestSql("postgres-conflict-expression.sql",
                        "INSERT INTO public.item (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET id = excluded.id;",
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL"),
                new TestSql("postgres-conflict-fixed.sql",
                        "INSERT INTO public.item (id, name) VALUES (1, 'one') "
                                + "ON CONFLICT (id) DO UPDATE SET name = 'fixed';",
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, "POSTGRESQL"),
                new TestSql("sqlserver-output.sql", "INSERT INTO dbo.item (id) OUTPUT inserted.id VALUES (1);\nGO\n",
                        ExportedSqlStatementReader.ExporterProfile.SSMS, "SQLSERVER"),
                new TestSql("oracle-sequence.sql", "INSERT INTO APP.ITEM (id) VALUES (ITEM_SEQ.NEXTVAL);\n/\n",
                        ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE"),
                new TestSql("oracle-errors.sql",
                        "INSERT INTO APP.ITEM (id) VALUES (1) LOG ERRORS INTO APP.ERRORS REJECT LIMIT UNLIMITED;\n/\n",
                        ExportedSqlStatementReader.ExporterProfile.ORACLE_SQL_DEVELOPER, "ORACLE"));

        for (TestSql test : malicious) {
            ExportedSqlStatementReader.Inspection inspection = strictInspection(directory, test.fileName(),
                    test.sql(), test.profile(), test.databaseType());
            assertFalse(inspection.supported(), test.fileName());
            assertEquals("THIRD_PARTY_DML_UNSAFE", inspection.unsupported().code(), test.fileName());
            assertEquals(0, inspection.dataStatementCount(), test.fileName());
        }
    }

    @Test
    void trustedPolicyRetainsQueryBearingDmlCompatibility(@TempDir Path directory) throws Exception {
        File source = write(directory, "trusted.sql", """
                INSERT INTO public.item SELECT nextval('item_seq');
                UPDATE public.item SET id = id + 1;
                """);

        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.inspect(
                source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.DBEAVER,
                "POSTGRESQL", null);

        assertTrue(inspection.supported(), () -> String.valueOf(inspection.unsupported()));
        assertEquals(2, inspection.dataStatementCount());
    }

    @Test
    void validatesThirdPartyExporterProfileAgainstKnownDatabaseDialect() {
        assertDoesNotThrow(() -> ExportedSqlStatementReader.requireCompatibleThirdPartyProfile(
                ExportedSqlStatementReader.ExporterProfile.HEIDISQL, "MARIADB"));
        assertDoesNotThrow(() -> ExportedSqlStatementReader.requireCompatibleThirdPartyProfile(
                ExportedSqlStatementReader.ExporterProfile.DBEAVER, "ORACLE"));
        assertThrows(IllegalArgumentException.class,
                () -> ExportedSqlStatementReader.requireCompatibleThirdPartyProfile(
                        ExportedSqlStatementReader.ExporterProfile.PGADMIN, "MYSQL"));
        assertThrows(IllegalArgumentException.class,
                () -> ExportedSqlStatementReader.requireCompatibleThirdPartyProfile(
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "custom-postgres"));
        assertThrows(IllegalArgumentException.class,
                () -> ExportedSqlStatementReader.requireCompatibleThirdPartyProfile(
                        ExportedSqlStatementReader.ExporterProfile.DBEAVER, "KINGBASE"));
    }

    @Test
    void enforcesSourceStatementAndDistinctTargetLimits(@TempDir Path directory) throws Exception {
        withProperty("chat2db.task.import.sql.max-source-bytes", "16", () -> {
            File source = write(directory, "source-limit.sql", "INSERT INTO item VALUES (1);\n");
            assertThrows(IllegalArgumentException.class, () -> ExportedSqlStatementReader.inspect(
                    source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT));
        });
        withProperty("chat2db.task.import.sql.max-statement-count", "2", () -> {
            File source = write(directory, "statement-limit.sql", """
                    INSERT INTO item VALUES (1);
                    INSERT INTO item VALUES (2);
                    INSERT INTO item VALUES (3);
                    """);
            assertThrows(IllegalArgumentException.class, () -> ExportedSqlStatementReader.inspect(
                    source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT));
        });
        withProperty("chat2db.task.import.sql.max-distinct-targets", "1", () -> {
            File source = write(directory, "target-limit.sql", """
                    INSERT INTO first_item VALUES (1);
                    INSERT INTO second_item VALUES (2);
                    """);
            assertThrows(IllegalArgumentException.class, () -> ExportedSqlStatementReader.inspect(
                    source, StandardCharsets.UTF_8, ExportedSqlStatementReader.ExporterProfile.NAVICAT));
        });
    }

    private static ExportedSqlStatementReader.Inspection strictInspection(Path directory, String fileName,
            String sql, ExportedSqlStatementReader.ExporterProfile profile, String databaseType) throws Exception {
        return ExportedSqlStatementReader.inspect(write(directory, fileName, sql), StandardCharsets.UTF_8,
                profile, databaseType, ExportedSqlStatementReader.StatementPolicy.THIRD_PARTY_LITERAL_VALUES,
                null);
    }

    private static void withProperty(String name, String value, CheckedAction action) throws Exception {
        String previous = System.getProperty(name);
        System.setProperty(name, value);
        try {
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, previous);
            }
        }
    }

    private record TestSql(String fileName, String sql,
                           ExportedSqlStatementReader.ExporterProfile profile, String databaseType) {
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static ExportedSqlStatementReader.UnsupportedDirective unsupported(Path directory, String fileName,
            String sql, ExportedSqlStatementReader.ExporterProfile profile) throws Exception {
        ExportedSqlStatementReader.Inspection inspection = ExportedSqlStatementReader.inspect(
                write(directory, fileName, sql), StandardCharsets.UTF_8, profile);
        assertFalse(inspection.supported());
        return inspection.unsupported();
    }

    private static File write(Path directory, String fileName, String content) throws Exception {
        return Files.writeString(directory.resolve(fileName), content, StandardCharsets.UTF_8).toFile();
    }

    private static File fixture(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(ExportedSqlStatementReaderTest.class.getResource(name)).toURI())
                .toFile();
    }
}
