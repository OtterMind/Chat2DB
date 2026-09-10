package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportFinalizationOptions;
import ai.chat2db.community.domain.api.model.task.ImportStagingPolicy;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFileImportTaskExecutorRoutingTest {

    @Test
    void routesEnabledValidationAndFinalizationThroughStaging() {
        assertTrue(DataFileImportTaskExecutor.stagingFeaturesRequested(ImportTaskSpec.builder()
                .validationOptions(ImportValidationOptions.builder().rowCount(true).build())
                .build()));
        assertTrue(DataFileImportTaskExecutor.stagingFeaturesRequested(ImportTaskSpec.builder()
                .finalizationOptions(ImportFinalizationOptions.builder().refreshStatistics(true).build())
                .build()));
    }

    @Test
    void keepsStandardImportOffStagingWhenEveryAdvancedOptionIsDisabled() {
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .cycleStrategy("REJECT")
                .stagingPolicy(ImportStagingPolicy.builder().enabled(false).allVarchar(false).twoPhase(false).build())
                .validationOptions(ImportValidationOptions.builder()
                        .sourceProfiling(false).rowCount(false).checksum(false).orphanCheck(false).build())
                .finalizationOptions(ImportFinalizationOptions.builder()
                        .resetSequences(false).rebuildIndexes(false).refreshStatistics(false).build())
                .build();

        assertFalse(DataFileImportTaskExecutor.stagingFeaturesRequested(spec));
    }

    @Test
    void matchesStagingImporterDatabaseFamilies() {
        assertTrue(DataFileImportTaskExecutor.supportsStagingDatabase("MYSQL"));
        assertTrue(DataFileImportTaskExecutor.supportsStagingDatabase("MARIADB"));
        assertTrue(DataFileImportTaskExecutor.supportsStagingDatabase("POSTGRESQL"));
        assertTrue(DataFileImportTaskExecutor.supportsStagingDatabase("KINGBASE"));
        assertTrue(DataFileImportTaskExecutor.supportsStagingDatabase("H2"));
        assertFalse(DataFileImportTaskExecutor.supportsStagingDatabase("ORACLE"));
        assertFalse(DataFileImportTaskExecutor.supportsStagingDatabase("SQLSERVER"));
    }

    @Test
    void failsClosedForUnsupportedAdvancedDatabase() {
        ImportTaskSpec advanced = ImportTaskSpec.builder()
                .validationOptions(ImportValidationOptions.builder().checksum(true).build())
                .build();

        assertThrows(TaskExecutionException.class,
                () -> DataFileImportTaskExecutor.requireSupportedStagingRequest(advanced, "ORACLE"));
        assertDoesNotThrow(
                () -> DataFileImportTaskExecutor.requireSupportedStagingRequest(advanced, "MYSQL"));
    }

    @Test
    void permitsDeferredConstraintsOnlyForPostgresqlFamilies() {
        ImportTaskSpec deferred = ImportTaskSpec.builder().cycleStrategy("DEFER_CONSTRAINTS").build();

        assertDoesNotThrow(
                () -> DataFileImportTaskExecutor.requireSupportedStagingRequest(deferred, "POSTGRESQL"));
        assertDoesNotThrow(
                () -> DataFileImportTaskExecutor.requireSupportedStagingRequest(deferred, "KINGBASE"));
        assertThrows(TaskExecutionException.class,
                () -> DataFileImportTaskExecutor.requireSupportedStagingRequest(deferred, "MYSQL"));
    }

    @Test
    void rejectsUnknownCycleStrategyBeforeImportWrites() {
        ImportTaskSpec unknown = ImportTaskSpec.builder().cycleStrategy("IGNORE").build();

        assertThrows(TaskExecutionException.class,
                () -> DataFileImportTaskExecutor.requireSupportedStagingRequest(unknown, "MYSQL"));
    }
}
