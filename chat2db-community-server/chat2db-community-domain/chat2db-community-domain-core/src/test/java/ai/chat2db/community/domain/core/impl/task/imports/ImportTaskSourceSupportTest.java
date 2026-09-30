package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportFinalizationOptions;
import ai.chat2db.community.domain.api.model.task.ImportRollbackOptions;
import ai.chat2db.community.domain.api.model.task.ImportStagingPolicy;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportTaskSourceSupportTest {

    @Test
    void validatesSourceCycleSamplingAndErrorControls() {
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(spec("untrusted", "REJECT", 5, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(spec("TRUSTED", "IGNORE", 5, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(spec("TRUSTED", "REJECT", 0, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(spec("TRUSTED", "REJECT", 101, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(spec("TRUSTED", "REJECT", 5,
                        ImportOptions.builder().onError("CONTINUE").build())));
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(spec("TRUSTED", "REJECT", 5,
                        ImportOptions.builder().onError("SKIP").maxErrors(-1).build())));
    }

    @Test
    void acceptsCaseInsensitiveSupportedControls() {
        assertDoesNotThrow(() -> ImportTaskSourceSupport.effectiveSources(
                spec("third_party", "staging_two_phase", 5,
                        ImportOptions.builder().onError("skip").maxErrors(0).build())));
    }

    @Test
    void preservesDistinctQuotedIdentifierCaseWhenCheckingDuplicateTargets() {
        ImportTaskSpec distinctQuotedTables = ImportTaskSpec.builder()
                .scope("SCHEMA")
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("public").build())
                .tableSources(List.of(
                        ImportTableSource.builder().databaseName("app").schemaName("public")
                                .tableName("Users").sourceFile("Users.csv").format("CSV").build(),
                        ImportTableSource.builder().databaseName("app").schemaName("public")
                                .tableName("users").sourceFile("users.csv").format("CSV").build()))
                .build();

        assertEquals(2, ImportTaskSourceSupport.effectiveSources(distinctQuotedTables).size());
    }

    @Test
    void rejectsSourcesOutsideTheSelectedScopeAnchor() {
        ImportTaskSpec wrongTable = ImportTaskSpec.builder()
                .scope("TABLE")
                .target(TaskTargetSnapshot.builder()
                        .databaseName("app").schemaName("public").tableName("orders").build())
                .tableSources(List.of(ImportTableSource.builder()
                        .databaseName("app").schemaName("public").tableName("customers")
                        .sourceFile("customers.csv").format("CSV").build()))
                .build();
        ImportTaskSpec missingSchemaDatabase = ImportTaskSpec.builder()
                .scope("SCHEMA")
                .target(TaskTargetSnapshot.builder().schemaName("public").build())
                .tableSources(List.of(ImportTableSource.builder()
                        .schemaName("public").tableName("orders").sourceFile("orders.csv").format("CSV").build()))
                .build();
        ImportTaskSpec missingDatabase = ImportTaskSpec.builder()
                .scope("DATABASE")
                .target(TaskTargetSnapshot.builder().build())
                .tableSources(List.of(ImportTableSource.builder()
                        .tableName("orders").sourceFile("orders.csv").format("CSV").build()))
                .build();

        assertEquals("Import table source is outside the selected table: app.public.customers",
                assertThrows(IllegalArgumentException.class,
                        () -> ImportTaskSourceSupport.effectiveSources(wrongTable)).getMessage());
        assertEquals("SCHEMA import scope requires a target database",
                assertThrows(IllegalArgumentException.class,
                        () -> ImportTaskSourceSupport.effectiveSources(missingSchemaDatabase)).getMessage());
        assertEquals("DATABASE import scope requires a target database",
                assertThrows(IllegalArgumentException.class,
                        () -> ImportTaskSourceSupport.effectiveSources(missingDatabase)).getMessage());
    }

    @Test
    void requiresAnExporterProfileForThirdPartySqlSources() {
        ImportTaskSpec thirdPartySql = ImportTaskSpec.builder()
                .taskType("SQL_FILE_IMPORT")
                .scope("TABLE")
                .sourceKind("THIRD_PARTY")
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .sourceFile("orders.sql")
                .format("SQL")
                .build();

        assertEquals("THIRD_PARTY SQL imports require an explicit SQL exporter profile",
                assertThrows(IllegalArgumentException.class,
                        () -> ImportTaskSourceSupport.effectiveSources(thirdPartySql)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.validateSqlImportControls(thirdPartySql));

        thirdPartySql.setOptions(ImportOptions.builder().sqlExporterProfile("MySQL Workbench").build());
        assertDoesNotThrow(() -> ImportTaskSourceSupport.effectiveSources(thirdPartySql));
        assertDoesNotThrow(() -> ImportTaskSourceSupport.validateSqlImportControls(thirdPartySql));
        thirdPartySql.setOptions(ImportOptions.builder().sqlExporterProfile("unknown-tool").build());
        assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(thirdPartySql));
    }

    @Test
    void rejectsEveryMultiTableManifestControlForSqlFileImports() {
        List<ImportTaskSpec> invalid = List.of(
                sqlSpec().tableSources(List.of(ImportTableSource.builder().tableName("orders").build())).build(),
                sqlSpec().logicalDependencies(List.of(ImportTableDependency.builder()
                        .parentTable("orders").childTable("items").build())).build(),
                sqlSpec().cycleStrategy("REJECT").build(),
                sqlSpec().stagingPolicy(ImportStagingPolicy.builder().enabled(false).build()).build(),
                sqlSpec().validationOptions(ImportValidationOptions.builder().orphanCheck(false).build()).build(),
                sqlSpec().finalizationOptions(ImportFinalizationOptions.builder().resetSequences(false).build()).build(),
                sqlSpec().rollbackOptions(ImportRollbackOptions.builder().rehearsal(false).build()).build(),
                sqlSpec().performanceSamplePercent(5).build(),
                sqlSpec().confirmedNoStrongRelations(false).build());

        for (ImportTaskSpec spec : invalid) {
            assertEquals("SQL file import does not accept multi-table manifest controls",
                    assertThrows(IllegalArgumentException.class,
                            () -> ImportTaskSourceSupport.validateSqlImportControls(spec)).getMessage());
        }
    }

    @Test
    void rejectsPerformanceSampleWithoutRollbackRehearsal() {
        ImportTaskSpec sampleOnly = spec("TRUSTED", "REJECT", 5, null);
        sampleOnly.setRollbackOptions(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ImportTaskSourceSupport.effectiveSources(sampleOnly));

        assertEquals("Import performance sample percent requires rollback rehearsal", error.getMessage());
    }

    @Test
    void shardRejectRolesAreStableDistinctAndFitTheStorageContract() {
        String first = TaskArtifactRole.rejectForShard("schema.orders/shard-with-a-long-identifier-0001");
        String second = TaskArtifactRole.rejectForShard("schema.orders/shard-with-a-long-identifier-0002");

        assertEquals(31, first.length());
        assertEquals(first,
                TaskArtifactRole.rejectForShard("schema.orders/shard-with-a-long-identifier-0001"));
        assertNotEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> TaskArtifactRole.rejectForShard(" "));
    }

    private static ImportTaskSpec spec(String sourceKind, String cycleStrategy, Integer samplePercent,
            ImportOptions options) {
        return ImportTaskSpec.builder()
                .scope("SCHEMA")
                .sourceKind(sourceKind)
                .cycleStrategy(cycleStrategy)
                .performanceSamplePercent(samplePercent)
                .rollbackOptions(samplePercent == null ? null
                        : ImportRollbackOptions.builder().rehearsal(true).build())
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("public").build())
                .tableSources(List.of(ImportTableSource.builder()
                        .databaseName("app").schemaName("public").tableName("orders")
                        .sourceFile("orders.csv").format("CSV").options(options).build()))
                .build();
    }

    private static ImportTaskSpec.ImportTaskSpecBuilder sqlSpec() {
        return ImportTaskSpec.builder()
                .taskType("SQL_FILE_IMPORT")
                .scope("DATABASE")
                .sourceKind("THIRD_PARTY")
                .mode("STANDARD")
                .options(ImportOptions.builder().sqlExporterProfile("NAVICAT").build());
    }
}
