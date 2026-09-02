package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.task.TableMaintenanceTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableMaintenanceTaskExecutorTest {

    @Test
    void executeLogsRunningResultRowsAndRefreshRequest() {
        AtomicReference<DbTableQueryRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<String> capturedOperation = new AtomicReference<>();
        AtomicReference<TaskExecutionContext> capturedContext = new AtomicReference<>();
        IDbTableService tableService = tableService((proxy, method, args) -> {
            if ("executeMaintenance".equals(method.getName())) {
                capturedRequest.set((DbTableQueryRequest) args[0]);
                capturedOperation.set((String) args[1]);
                capturedContext.set((TaskExecutionContext) args[2]);
                return List.of(maintenanceResult(true, "status", "OK"));
            }
            throw new UnsupportedOperationException(method.getName());
        });
        RecordingContext context = new RecordingContext();

        new TableMaintenanceTaskExecutor(tableService).execute(spec("ANALYZE"), context);

        assertEquals("orders", capturedRequest.get().getTableName());
        assertEquals("ANALYZE", capturedOperation.get());
        assertSame(context, capturedContext.get());
        assertEquals(List.of(
                        new Progress(5, TaskStage.QUERYING.name(), "Preparing ANALYZE TABLE for orders"),
                        new Progress(85, TaskStage.QUERYING.name(), "ANALYZE TABLE completed for orders"),
                        new Progress(95, TaskStage.FINALIZING.name(), "Refreshing metadata for orders")),
                context.progress);
        assertEquals(List.of(
                        TaskEventCode.TABLE_MAINTENANCE_STARTED.name(),
                        TaskEventCode.TABLE_MAINTENANCE_RESULT.name(),
                        TaskEventCode.TABLE_MAINTENANCE_REFRESH_REQUESTED.name(),
                        TaskEventCode.TABLE_MAINTENANCE_COMPLETED.name()),
                context.events.stream().map(RecordedEvent::code).toList());
        RecordedEvent resultEvent = context.singleEvent(TaskEventCode.TABLE_MAINTENANCE_RESULT.name());
        assertEquals("ANALYZE TABLE result for shop.orders: status OK", resultEvent.message());
        assertEquals("shop.orders", resultEvent.details().get("Table"));
        assertEquals("analyze", resultEvent.details().get("Op"));
        assertEquals("status", resultEvent.details().get("Msg_type"));
        assertEquals("OK", resultEvent.details().get("Msg_text"));
        RecordedEvent refreshEvent = context.singleEvent(TaskEventCode.TABLE_MAINTENANCE_REFRESH_REQUESTED.name());
        assertEquals(7L, refreshEvent.details().get("dataSourceId"));
        assertEquals("shop", refreshEvent.details().get("databaseName"));
        assertEquals("orders", refreshEvent.details().get("tableName"));
    }

    @Test
    void executeLogsActionableFailureRowsBeforeFailingTask() {
        IDbTableService tableService = tableService((proxy, method, args) ->
                List.of(maintenanceResult(false, "error", "The storage engine for the table does not support repair")));
        RecordingContext context = new RecordingContext();

        TaskExecutionException exception = assertThrows(TaskExecutionException.class,
                () -> new TableMaintenanceTaskExecutor(tableService).execute(spec("REPAIR"), context));

        RecordedEvent resultEvent = context.singleEvent(TaskEventCode.TABLE_MAINTENANCE_RESULT.name());
        assertEquals("REPAIR TABLE result for shop.orders: error The storage engine for the table does not support repair",
                resultEvent.message());
        assertEquals("The storage engine for the table does not support repair",
                resultEvent.details().get("Msg_text"));
        assertEquals(TaskErrorCode.TABLE_MAINTENANCE_FAILED.name(), exception.getCode());
        assertTrue(context.events.stream()
                .noneMatch(event -> TaskEventCode.TABLE_MAINTENANCE_COMPLETED.name().equals(event.code())));
    }

    @SuppressWarnings("unchecked")
    private static IDbTableService tableService(java.lang.reflect.InvocationHandler handler) {
        return (IDbTableService) Proxy.newProxyInstance(TableMaintenanceTaskExecutorTest.class.getClassLoader(),
                new Class<?>[] {IDbTableService.class}, handler);
    }

    private static TableMaintenanceTaskSpec spec(String operationType) {
        return TableMaintenanceTaskSpec.builder()
                .taskType(TaskType.TABLE_MAINTENANCE.name())
                .operationType(operationType)
                .target(TaskTargetSnapshot.builder()
                        .dataSourceId(7L)
                        .databaseName("shop")
                        .tableName("orders")
                        .build())
                .build();
    }

    private static ExecuteResponse maintenanceResult(Boolean success, String msgType, String msgText) {
        return ExecuteResponse.builder()
                .success(success)
                .sql("ANALYZE TABLE `shop`.`orders`")
                .headerList(List.of(
                        Header.builder().name("Table").build(),
                        Header.builder().name("Op").build(),
                        Header.builder().name("Msg_type").build(),
                        Header.builder().name("Msg_text").build()))
                .dataList(List.of(List.of(
                        ResultCell.of("shop.orders"),
                        ResultCell.of("analyze"),
                        ResultCell.of(msgType),
                        ResultCell.of(msgText))))
                .build();
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final List<Progress> progress = new ArrayList<>();
        private final List<RecordedEvent> events = new ArrayList<>();

        @Override
        public void reportProgress(int progress, String stage, String message) {
            this.progress.add(new Progress(progress, stage, message));
        }

        @Override
        public void logInfo(String code, String message) {
            logInfo(code, message, Map.of());
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
            events.add(new RecordedEvent("INFO", code, message, details));
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
            events.add(new RecordedEvent("WARN", code, message, details));
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
            events.add(new RecordedEvent("ERROR", code, message, details));
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ai.chat2db.community.domain.api.model.task.ArtifactDraft createArtifact(String outputDirectory,
                String fileName, String mediaType) {
            return null;
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

        RecordedEvent singleEvent(String code) {
            return events.stream().filter(event -> code.equals(event.code())).findFirst().orElseThrow();
        }
    }

    private record Progress(int progress, String stage, String message) {
    }

    private record RecordedEvent(String level, String code, String message, Map<String, Object> details) {
    }
}
