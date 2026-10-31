package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoint semantics: the stored position counts committed batches only, follows every commit,
 * and never fails the import when the storage rejects the write.
 */
class ImportCheckpointWriterTest {

    @Test
    void positionCountsCommittedBatchesOnly() {
        RecordingContext context = new RecordingContext();
        ImportCheckpointWriter writer = new ImportCheckpointWriter(context, spec(null, "INSERT"));

        assertEquals(0L, writer.processedRows());
        assertEquals(List.of(), context.checkpoints, "reading rows must not move the position");

        writer.batchCommitted(1000);
        assertEquals(1000L, writer.processedRows());
        writer.batchCommitted(2000);
        assertEquals(2000L, writer.processedRows());

        assertEquals(List.of(1000L, 2000L), positions(context));
    }

    @Test
    void everyCommittedBatchIsWrittenOnceAndUnchangedPositionsAreNotRewritten() {
        RecordingContext context = new RecordingContext();
        ImportCheckpointWriter writer = new ImportCheckpointWriter(context, spec(null, "INSERT"));

        writer.batchCommitted(1000);
        writer.batchCommitted(1000);
        writer.flush();

        assertEquals(List.of(1000L), positions(context),
                "a cancelled task can no longer write, so the durable position must not be throttled");
    }

    @Test
    void resumedRunStoresTheAbsoluteSourcePosition() {
        RecordingContext context = new RecordingContext();
        ImportCheckpointWriter writer = new ImportCheckpointWriter(context, spec(1500L, "INSERT"));

        writer.batchCommitted(500);

        assertEquals(2000L, writer.processedRows());
        assertEquals(List.of(2000L), positions(context));
    }

    @Test
    void checkpointCarriesTheTargetTableAndTheConflictPolicy() {
        RecordingContext context = new RecordingContext();
        ImportCheckpointWriter writer = new ImportCheckpointWriter(context, spec(null, "IGNORE"));

        writer.batchCommitted(7);

        ImportCheckpoint checkpoint = ImportCheckpoint.fromJson(context.checkpoints.get(0));
        assertEquals(7L, checkpoint.processedRows());
        assertEquals("TARGET_ROWS", checkpoint.targetTable());
        assertEquals("IGNORE", checkpoint.conflictPolicy());
        assertTrue(context.checkpoints.get(0).contains("\"processedRows\""));
    }

    @Test
    void failingStorageOnlyReportsAnEvent() {
        RecordingContext context = new RecordingContext();
        context.failCheckpoints = true;
        ImportCheckpointWriter writer = new ImportCheckpointWriter(context, spec(null, "INSERT"));

        writer.batchCommitted(1000);

        assertEquals(1000L, writer.processedRows(), "the in-memory position still advances");
        assertEquals(List.of(TaskEventCode.IMPORT_CHECKPOINT_FAILED.name()), context.warnings);
    }

    @Test
    void malformedCheckpointsAreRejectedClearly() {
        for (String malformed : new String[] {null, "  ", "{}", "{not json", "[1,2]"}) {
            assertThrows(IllegalArgumentException.class, () -> ImportCheckpoint.fromJson(malformed),
                    String.valueOf(malformed));
        }
    }

    private static List<Long> positions(RecordingContext context) {
        return context.checkpoints.stream().map(json -> ImportCheckpoint.fromJson(json).processedRows()).toList();
    }

    private static ImportTaskSpec spec(Long resumeFromRow, String duplicatePolicy) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile("/tmp/input.csv")
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .resumeFromRow(resumeFromRow)
                .duplicatePolicy(duplicatePolicy)
                .build();
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final List<String> checkpoints = new ArrayList<>();

        private final List<String> warnings = new ArrayList<>();

        private boolean failCheckpoints;

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
            warnings.add(code);
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public void onStatementCreated(java.sql.Statement statement) {
        }

        @Override
        public void onStatementClosed(java.sql.Statement statement) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void saveCheckpoint(String checkpointJson) {
            if (failCheckpoints) {
                throw new IllegalStateException("The task store is unavailable");
            }
            checkpoints.add(checkpointJson);
        }
    }
}
