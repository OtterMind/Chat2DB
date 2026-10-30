package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExportProgressReporterTest {

    @Test
    void reportsRowsAndRateWithoutMovingTheStageProgress() {
        RecordingContext context = new RecordingContext();
        AtomicLong clock = new AtomicLong();
        ExportProgressReporter reporter = new ExportProgressReporter(context, 10, clock::get);

        reporter.addRows(1);
        assertEquals(10, context.structured().get(0).getProgress());
        assertEquals(1L, context.structured().get(0).getProcessedRows());
        assertNull(context.structured().get(0).getRowsPerSecond());

        clock.set(1_000_000_000L);
        reporter.addRows(999);
        assertEquals(1_000L, context.structured().get(1).getProcessedRows());
        assertEquals(1_000.0d, context.structured().get(1).getRowsPerSecond(), 0.001d);

        clock.set(1_500_000_000L);
        reporter.addRows(500);
        assertEquals(2, context.structured().size(), "a row and time interval must both be short");

        reporter.stage(20, "EXPORTING", "Exporting table data");
        assertEquals(20, context.progressValues().get(context.progressValues().size() - 1));

        clock.set(2_500_000_000L);
        reporter.addRows(500);
        assertEquals(20, context.structured().get(2).getProgress(), "the stage value is kept");
        assertEquals(2_000L, context.structured().get(2).getProcessedRows());
        assertEquals(800.0d, context.structured().get(2).getRowsPerSecond(), 0.001d);
        assertNull(context.structured().get(2).getTotalRows());
        assertNull(context.structured().get(2).getEtaSeconds());

        reporter.addRows(10);
        reporter.flush();
        assertEquals(2_010L, context.structured().get(3).getProcessedRows());
        assertEquals(20, context.structured().get(3).getProgress());
    }

    @Test
    void exportLoggerForwardsRowsToTheTask() {
        RecordingContext context = new RecordingContext();
        ExportProgressReporter reporter = new ExportProgressReporter(context, 10, () -> 0L);
        ExportProgressLogger logger = new ExportProgressLogger(context, "CSV", "orders", reporter, 1_000L);

        logger.recordExportedRows(5);
        logger.queryCompleted("Table data read completed");

        TaskProgress progress = context.structured().get(context.structured().size() - 1);
        assertEquals(5L, progress.getProcessedRows());
        assertEquals(10, progress.getProgress());
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final List<Integer> progressValues = new ArrayList<>();

        private final List<TaskProgress> structured = new ArrayList<>();

        @Override
        public void reportProgress(int progress, String stage, String message) {
            progressValues.add(progress);
        }

        @Override
        public void reportProgress(TaskProgress progress) {
            structured.add(progress);
            progressValues.add(progress.getProgress());
        }

        private List<Integer> progressValues() {
            return progressValues;
        }

        private List<TaskProgress> structured() {
            return structured;
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
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
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }
}
