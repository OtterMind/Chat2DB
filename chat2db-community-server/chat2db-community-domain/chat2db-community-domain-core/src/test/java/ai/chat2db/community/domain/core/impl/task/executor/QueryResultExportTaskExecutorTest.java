package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.enums.ExportTypeEnum;
import ai.chat2db.community.domain.api.model.db.DbDmlExportPlan;
import ai.chat2db.community.domain.api.model.request.db.DbDmlExportRequest;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionResult;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.db.IDbDmlExportService;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryResultExportTaskExecutorTest {

    @Test
    void xlsIsRejectedBeforeExportServiceIsCalled(@TempDir Path tempDirectory) {
        RecordingExportService service = new RecordingExportService();
        QueryResultExportTaskExecutor executor = new QueryResultExportTaskExecutor(service);

        TaskExecutionException exception = assertThrows(TaskExecutionException.class,
                () -> executor.execute(spec("XLS"), new RecordingContext(tempDirectory)));

        assertEquals(TaskErrorCode.EXPORT_FAILED.name(), exception.getCode());
        assertEquals(0, service.prepareCalls);
        assertEquals(0, service.exportCalls);
    }

    @Test
    void xlsxUsesExcelExportType(@TempDir Path tempDirectory) {
        RecordingExportService service = new RecordingExportService();
        QueryResultExportTaskExecutor executor = new QueryResultExportTaskExecutor(service);

        TaskExecutionResult result = executor.execute(spec("XLSX"), new RecordingContext(tempDirectory));

        assertNotNull(result.getArtifactDraft());
        assertEquals(1, service.prepareCalls);
        assertEquals(1, service.exportCalls);
        assertEquals(ExportTypeEnum.EXCEL.name(), service.preparedRequest.getExportType());
    }

    @Test
    void rowProgressIsThrottledAndCompletionIncludesRemainder(@TempDir Path tempDirectory) {
        RecordingExportService service = new RecordingExportService(2_001);
        RecordingContext context = new RecordingContext(tempDirectory);

        new QueryResultExportTaskExecutor(service).execute(spec("CSV"), context);

        List<RecordedEvent> progressEvents = context.events(TaskEventCode.ROWS_EXPORTED.name());
        assertEquals(2, progressEvents.size());
        assertEquals(1_000L, progressEvents.get(0).details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
        assertEquals(2_000L, progressEvents.get(1).details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
        RecordedEvent completed = context.singleEvent(TaskEventCode.QUERY_COMPLETED.name());
        assertEquals(2_001L, completed.details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
        assertEquals("Query result read completed: 2001 rows", completed.message());
        assertEquals("Finalizing CSV export file",
                context.singleEvent(TaskEventCode.FILE_FINALIZING.name()).message());
    }

    @Test
    void exportEventsDescribeReadingRowsAndFileFinalizationInOrder(@TempDir Path tempDirectory) {
        RecordingContext context = new RecordingContext(tempDirectory);

        new QueryResultExportTaskExecutor(new RecordingExportService(1_000)).execute(spec("XLSX"), context);

        assertEquals(List.of(
                        TaskEventCode.EXPORT_STARTED.name(),
                        TaskEventCode.QUERY_STARTED.name(),
                        TaskEventCode.ROWS_EXPORTED.name(),
                        TaskEventCode.QUERY_COMPLETED.name(),
                        TaskEventCode.FILE_FINALIZING.name()),
                context.recordedEvents.stream().map(RecordedEvent::code).toList());
        assertEquals("Query result read completed: 1000 rows",
                context.singleEvent(TaskEventCode.QUERY_COMPLETED.name()).message());
        assertEquals("Finalizing XLSX export file",
                context.singleEvent(TaskEventCode.FILE_FINALIZING.name()).message());
    }

    @Test
    void completionFlushesFinalRowCountForEverySupportedFormat(@TempDir Path tempDirectory) {
        Map<String, String> expectedExportTypes = Map.of(
                "CSV", ExportTypeEnum.CSV.name(),
                "XLSX", ExportTypeEnum.EXCEL.name(),
                "SQL", ExportTypeEnum.INSERT.name());

        for (Map.Entry<String, String> entry : expectedExportTypes.entrySet()) {
            RecordingExportService service = new RecordingExportService(3);
            RecordingContext context = new RecordingContext(tempDirectory);

            new QueryResultExportTaskExecutor(service).execute(spec(entry.getKey()), context);

            assertEquals(entry.getValue(), service.preparedRequest.getExportType());
            assertEquals(0, context.events(TaskEventCode.ROWS_EXPORTED.name()).size());
            RecordedEvent completed = context.singleEvent(TaskEventCode.QUERY_COMPLETED.name());
            assertEquals(3L, completed.details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
            assertEquals("Query result read completed: 3 rows", completed.message());
        }
    }

    @Test
    void specificExportFailureIsNotReplacedByGenericMessage(@TempDir Path tempDirectory) {
        RecordingExportService service = new RecordingExportService();
        service.exportFailure = new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                "Could not export query result", "The output stream was closed", null);

        TaskExecutionException exception = assertThrows(TaskExecutionException.class,
                () -> new QueryResultExportTaskExecutor(service).execute(spec("XLSX"),
                        new RecordingContext(tempDirectory)));

        assertEquals(TaskErrorCode.EXPORT_FAILED.name(), exception.getCode());
        assertEquals("Could not export query result: The output stream was closed", exception.publicMessage());
    }

    private static ExportTaskSpec spec(String format) {
        return ExportTaskSpec.builder()
                .taskType(TaskType.QUERY_RESULT_EXPORT.name())
                .format(format)
                .sql("select * from orders")
                .target(TaskTargetSnapshot.builder()
                        .databaseName("app")
                        .schemaName("public")
                        .build())
                .build();
    }

    private static final class RecordingExportService implements IDbDmlExportService {

        private int prepareCalls;
        private int exportCalls;
        private DbDmlExportRequest preparedRequest;
        private final int rowsToExport;
        private TaskExecutionException exportFailure;

        private RecordingExportService() {
            this(0);
        }

        private RecordingExportService(int rowsToExport) {
            this.rowsToExport = rowsToExport;
        }

        @Override
        public String resolveTableName(String sql, String databaseName, String schemaName) {
            return "orders";
        }

        @Override
        public DbDmlExportPlan prepareExport(DbDmlExportRequest request) {
            prepareCalls++;
            preparedRequest = request;
            return DbDmlExportPlan.builder()
                    .fileName("orders.xlsx")
                    .exportType(ExportTypeEnum.EXCEL)
                    .exportRequest(request)
                    .build();
        }

        @Override
        public void export(DbDmlExportRequest request, OutputStream outputStream,
                ISqlExecutionStatementListener statementListener, Runnable cancellationChecker,
                LongConsumer exportedRowsListener, Runnable fileFinalizationListener) throws IOException {
            exportCalls++;
            if (exportFailure != null) {
                throw exportFailure;
            }
            for (int i = 0; i < rowsToExport; i++) {
                outputStream.write(1);
                exportedRowsListener.accept(1L);
            }
            fileFinalizationListener.run();
        }
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final Path tempDirectory;

        private final List<RecordedEvent> recordedEvents = new ArrayList<>();

        private RecordingContext(Path tempDirectory) {
            this.tempDirectory = tempDirectory;
        }

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
            logInfo(code, message, Map.of());
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
            recordedEvents.add(new RecordedEvent(code, message, details));
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
            return ArtifactDraft.builder()
                    .temporaryFile(tempDirectory.resolve("query-export.part").toFile())
                    .targetFile(tempDirectory.resolve(fileName).toFile())
                    .mediaType(mediaType)
                    .build();
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }

        private List<RecordedEvent> events(String code) {
            return recordedEvents.stream().filter(event -> code.equals(event.code())).toList();
        }

        private RecordedEvent singleEvent(String code) {
            List<RecordedEvent> events = events(code);
            assertEquals(1, events.size());
            return events.get(0);
        }
    }

    private record RecordedEvent(String code, String message, Map<String, Object> details) {
    }
}
