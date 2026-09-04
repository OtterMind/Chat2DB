package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.task.extension.TaskOperation;
import ai.chat2db.community.domain.api.model.task.extension.TaskSubmissionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.domain.core.impl.task.extension.TaskExtensionManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTaskManagerTest {

    @TempDir
    Path tempDirectory;

    private LocalTaskManager taskManager;

    @AfterEach
    void tearDown() {
        if (taskManager != null) {
            taskManager.shutdown();
        }
    }

    @Test
    void successfulTaskHasOneImmutableTerminalResult() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        taskManager = manager(storage, (spec, context) -> {});
        Task task = newTask();

        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null);

        assertTrue(storage.awaitTerminal());
        assertEquals(TaskStatus.SUCCESS.name(), storage.get(task.getId()).orElseThrow().getStatus());
        assertEquals(1, storage.terminalTransitionCount());
    }

    @Test
    void submissionAndExecutionExtensionsWrapTheNewTaskEngine() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        List<String> events = new ArrayList<>();
        AtomicReference<TaskSubmissionContext> captured = new AtomicReference<>();
        TaskExtensionManager extensionManager = new TaskExtensionManager(
                List.of(context -> {
                    events.add("capture");
                    captured.set(context);
                }),
                List.of(context -> events.add("guard")));
        taskManager = manager(storage, (spec, context) -> events.add("execute"), extensionManager);
        Task task = newTask();
        ExportTaskSpec spec = spec();
        spec.getTarget().setDatabaseName("shop");
        spec.getTarget().setSchemaName("public");
        spec.setTableNames(List.of("orders"));

        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec, null, null);

        assertTrue(storage.awaitTerminal());
        assertEquals(List.of("capture", "guard", "execute"), events);
        assertEquals(task.getId(), captured.get().getTaskId());
        assertEquals(TaskType.QUERY_RESULT_EXPORT, captured.get().getTaskType());
        assertEquals(TaskOperation.EXPORT, captured.get().getOperation());
        assertEquals("shop", captured.get().getDatabaseName());
        assertEquals("public", captured.get().getSchemaName());
        assertEquals(List.of("orders"), captured.get().getTableNames());
    }

    @Test
    void rejectedSubmissionSnapshotDoesNotLeavePendingTask() {
        TestTaskStorage storage = new TestTaskStorage();
        TaskExtensionManager extensionManager = new TaskExtensionManager(
                List.of(context -> {
                    throw new IllegalStateException("Snapshot rejected");
                }), List.of());
        taskManager = manager(storage, (spec, context) -> {}, extensionManager);
        Task task = newTask();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null));

        assertEquals("Snapshot rejected", error.getMessage());
        Task rejected = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), rejected.getStatus());
        assertEquals(TaskErrorCode.TASK_SUBMISSION_REJECTED.name(), rejected.getErrorCode());
        assertEquals("Task submission rejected", rejected.getErrorMessage());
        assertTrue(storage.listNonTerminalTasks().isEmpty());
    }

    @Test
    void executionExceptionCannotBeOverwrittenBySuccess() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        taskManager = manager(storage, (spec, context) -> {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Could not export query result", "The output stream was closed",
                    new IllegalArgumentException("password=secret\n\tat internal.Stack"));
        });
        Task task = newTask();

        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null);

        assertTrue(storage.awaitTerminal());
        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertEquals(TaskErrorCode.EXPORT_FAILED.name(), failed.getErrorCode());
        assertEquals("Could not export query result: The output stream was closed", failed.getErrorMessage());
        assertEquals(failed.getErrorMessage(), failed.getProgressMessage());
        TaskEvent failedEvent = storage.listEvents(task.getId(), 0, 100).stream()
                .filter(event -> TaskEventCode.TASK_FAILED.name().equals(event.getCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(TaskEventLevel.ERROR.name(), failedEvent.getLevel());
        assertEquals(failed.getErrorMessage(), failedEvent.getMessage());
        assertEquals(TaskErrorCode.EXPORT_FAILED.name(),
                failedEvent.getDetails().get(TaskConstants.ERROR_CODE_DETAIL_KEY));
        assertEquals("The output stream was closed",
                failedEvent.getDetails().get(TaskConstants.ERROR_REASON_DETAIL_KEY));
        assertFalse(failedEvent.getDetails().containsKey("causeType"));
        assertFalse(failedEvent.getMessage().contains("password"));
        assertFalse(failedEvent.getMessage().contains("internal.Stack"));
        assertEquals(1, storage.terminalTransitionCount());
    }

    @Test
    void executionExceptionDetailsUseBoundedSingleLineReason() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        String unboundedReason = "Export failed\n"
                + "x".repeat(TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH);
        taskManager = manager(storage, (spec, context) -> {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Could not export query result", unboundedReason, null);
        });
        Task task = newTask();

        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null);

        assertTrue(storage.awaitTerminal());
        TaskEvent failedEvent = storage.listEvents(task.getId(), 0, 100).stream()
                .filter(event -> TaskEventCode.TASK_FAILED.name().equals(event.getCode()))
                .findFirst()
                .orElseThrow();
        String reason = (String) failedEvent.getDetails().get(TaskConstants.ERROR_REASON_DETAIL_KEY);
        assertEquals(TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH, reason.length());
        assertFalse(reason.contains("\n"));
        assertTrue(reason.startsWith("Export failed "));
        assertTrue(reason.endsWith("..."));
    }

    @Test
    void confirmedUserExitFailsActiveTaskWithStableReason() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        taskManager = manager(storage, (spec, context) -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.checkCancelled();
        });
        Task task = newTask();
        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(1, taskManager.activeTaskCount(null, null));

        taskManager.prepareForUserExit(null, null);
        release.countDown();

        assertTrue(storage.awaitTerminal());
        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertEquals(TaskErrorCode.USER_EXITED.name(), failed.getErrorCode());
        assertTrue(storage.listEvents(task.getId(), 0, 100).stream()
                .anyMatch(event -> TaskEventCode.USER_EXITED.name().equals(event.getCode())));
        assertEquals(1, storage.terminalTransitionCount());
    }

    @Test
    void confirmedUserExitRejectsNewTaskBeforeItIsPersisted() {
        TestTaskStorage storage = new TestTaskStorage();
        taskManager = manager(storage, (spec, context) -> {});

        taskManager.prepareForUserExit(null, null);

        assertThrows(RejectedExecutionException.class,
                () -> taskManager.submit(newTask(), event(TaskEventCode.TASK_CREATED.name()),
                        spec(), null, null));
        assertTrue(storage.listNonTerminalTasks().isEmpty());
    }

    @Test
    void explicitCancelMarksRunningTaskCancelled() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        taskManager = manager(storage, (spec, context) -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.checkCancelled();
        });
        Task task = newTask();
        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(taskManager.cancel(task));
        release.countDown();

        assertTrue(storage.awaitTerminal());
        Task cancelled = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED.name(), cancelled.getStatus());
        assertEquals(TaskStage.CANCELLED.name(), cancelled.getStage());
        assertTrue(storage.listEvents(task.getId(), 0, 100).stream()
                .anyMatch(event -> TaskEventCode.TASK_CANCELLED.name().equals(event.getCode())));
        assertEquals(1, storage.terminalTransitionCount());
    }

    @Test
    void abortedUserExitAllowsNewTasksAgain() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        taskManager = manager(storage, (spec, context) -> {});
        taskManager.prepareForUserExit(null, null);

        taskManager.abortUserExit();
        Task submitted = taskManager.submit(newTask(), event(TaskEventCode.TASK_CREATED.name()),
                spec(), null, null);

        assertNotNull(submitted);
        assertTrue(storage.awaitTerminal());
        assertEquals(TaskStatus.SUCCESS.name(), storage.get(submitted.getId()).orElseThrow().getStatus());
    }

    @Test
    void executionContextBindingFailureFailsTaskAndCleansRunningRegistry() {
        TestTaskStorage storage = new TestTaskStorage();
        Task task = storage.create(newTask(), event(TaskEventCode.TASK_CREATED.name()));
        RunningTask runningTask = new RunningTask(task.getId());
        RunningTaskRegistry registry = new RunningTaskRegistry();
        registry.register(runningTask);
        AtomicBoolean executed = new AtomicBoolean();
        TaskExecutor<ExportTaskSpec> executor = new TaskExecutor<>() {
            @Override
            public String taskType() {
                return TaskType.QUERY_RESULT_EXPORT.name();
            }

            @Override
            public Class<ExportTaskSpec> specType() {
                return ExportTaskSpec.class;
            }

            @Override
            public void execute(ExportTaskSpec spec, TaskExecutionContext context) {
                executed.set(true);
            }
        };
        ConnectInfo invalidConnectInfo = new ConnectInfo() {
            @Override
            public DriverConfig getDriverConfig() {
                throw new IllegalStateException("Invalid task connection context");
            }
        };
        TaskRunner<ExportTaskSpec> runner = new TaskRunner<>(
                new TaskSubmission<>(task.getId(), spec(), null, invalidConnectInfo,
                        new TaskSubmissionContext(task.getId(), TaskType.QUERY_RESULT_EXPORT, null,
                                null, null, List.of(), TaskOperation.EXPORT).toExecutionContext()),
                runningTask, registry, storage, executor, new ArtifactService(), emptyExtensionManager());

        runner.run();

        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertEquals(TaskErrorCode.TASK_INTERNAL_ERROR.name(), failed.getErrorCode());
        assertEquals("Task execution failed", failed.getErrorMessage());
        assertTrue(storage.listEvents(task.getId(), 0, 100).stream()
                .filter(event -> TaskEventCode.TASK_FAILED.name().equals(event.getCode()))
                .allMatch(event -> "Task execution failed".equals(event.getMessage())));
        assertFalse(executed.get());
        assertNull(registry.get(task.getId()));
    }

    @Test
    void containerShutdownFailsActiveTaskAsApplicationTerminated() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        taskManager = manager(storage, (spec, context) -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.checkCancelled();
        });
        Task task = newTask();
        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        taskManager.shutdown();
        release.countDown();

        assertTrue(storage.awaitTerminal());
        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertEquals(TaskErrorCode.APPLICATION_TERMINATED.name(), failed.getErrorCode());
        assertTrue(storage.listEvents(task.getId(), 0, 100).stream()
                .anyMatch(event -> TaskEventCode.APPLICATION_TERMINATED.name().equals(event.getCode())));
    }

    @Test
    void startupReconciliationDoesNotResubmitInterruptedTask() {
        TestTaskStorage storage = new TestTaskStorage();
        Task task = storage.create(newTask(), event(TaskEventCode.TASK_CREATED.name()));
        taskManager = manager(storage, (spec, context) -> {});

        taskManager.reconcileInterruptedTasks();

        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertEquals(TaskErrorCode.APPLICATION_TERMINATED.name(), failed.getErrorCode());
        assertEquals(0, taskManager.activeTaskCount(null, null));
    }

    @Test
    void startupReconciliationCleansPreparedAndPublishedArtifactPaths() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        Task task = storage.create(newTask(), event(TaskEventCode.TASK_CREATED.name()));
        Path temporary = Files.writeString(
                tempDirectory.resolve(".task-" + task.getId() + "-draft.csv.part"), "temporary");
        Path target = Files.writeString(tempDirectory.resolve("published.csv"), "published");
        storage.appendEvent(TaskEvent.builder()
                .taskId(task.getId())
                .level(TaskEventLevel.INFO.name())
                .code(TaskEventCode.ARTIFACT_PREPARED.name())
                .message("Artifact prepared")
                .details(Map.of(
                        TaskConstants.ARTIFACT_TEMPORARY_PATH_DETAIL_KEY, temporary.toString(),
                        TaskConstants.ARTIFACT_TARGET_PATH_DETAIL_KEY, target.toString()))
                .build());
        storage.appendEvent(TaskEvent.builder()
                .taskId(task.getId())
                .level(TaskEventLevel.INFO.name())
                .code(TaskEventCode.ARTIFACT_PUBLISHED.name())
                .message("Artifact published")
                .details(Map.of(TaskConstants.ARTIFACT_ID_DETAIL_KEY, target.toString()))
                .build());
        taskManager = manager(storage, (spec, context) -> {});

        taskManager.reconcileInterruptedTasks();

        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(target));
        assertEquals(TaskStatus.FAILED.name(), storage.get(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void startupReconciliationPreservesTargetWithoutPublishedEvent() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        Task task = storage.create(newTask(), event(TaskEventCode.TASK_CREATED.name()));
        Path temporary = Files.writeString(
                tempDirectory.resolve(".task-" + task.getId() + "-draft.csv.part"), "temporary");
        Path target = Files.writeString(tempDirectory.resolve("export.csv"), "user-created");
        storage.appendEvent(TaskEvent.builder()
                .taskId(task.getId())
                .level(TaskEventLevel.INFO.name())
                .code(TaskEventCode.ARTIFACT_PREPARED.name())
                .message("Artifact prepared")
                .details(Map.of(
                        TaskConstants.ARTIFACT_TEMPORARY_PATH_DETAIL_KEY, temporary.toString(),
                        TaskConstants.ARTIFACT_TARGET_PATH_DETAIL_KEY, target.toString()))
                .build());
        taskManager = manager(storage, (spec, context) -> {});

        taskManager.reconcileInterruptedTasks();

        assertFalse(Files.exists(temporary));
        assertEquals("user-created", Files.readString(target));
        assertEquals(TaskStatus.FAILED.name(), storage.get(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void startupReconciliationRetriesCleanupForExitFailedTask() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        Task task = storage.create(newTask(), event(TaskEventCode.TASK_CREATED.name()));
        assertTrue(storage.compareAndSetStatus(task.getId(), TaskStatus.PENDING.name(), TaskStatus.FAILED.name(),
                TaskStatusPatch.builder()
                        .errorCode(TaskErrorCode.USER_EXITED.name())
                        .errorMessage("User exited")
                        .finishedAt(new Date())
                        .build(),
                event(TaskEventCode.USER_EXITED.name())));
        Path temporary = Files.writeString(
                tempDirectory.resolve(".task-" + task.getId() + "-retry.csv.part"), "temporary");
        storage.appendEvent(TaskEvent.builder()
                .taskId(task.getId())
                .level(TaskEventLevel.INFO.name())
                .code(TaskEventCode.ARTIFACT_PREPARED.name())
                .message("Artifact prepared")
                .details(Map.of(TaskConstants.ARTIFACT_TEMPORARY_PATH_DETAIL_KEY, temporary.toString()))
                .build());
        taskManager = manager(storage, (spec, context) -> {});

        taskManager.reconcileInterruptedTasks();

        assertFalse(Files.exists(temporary));
        assertEquals(TaskErrorCode.USER_EXITED.name(), storage.get(task.getId()).orElseThrow().getErrorCode());
        assertEquals(TaskEventCode.ARTIFACT_CLEANUP_COMPLETED.name(),
                storage.listEventsBefore(task.getId(), null, 1).get(0).getCode());

        Files.writeString(temporary, "recreated");
        taskManager.reconcileInterruptedTasks();
        assertTrue(Files.exists(temporary));
    }

    @Test
    void artifactPreparationIsPersistedBeforePublication() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        taskManager = manager(storage, (spec, context) -> {
            context.createArtifact(tempDirectory.toString(), "export.csv", "text/csv");
            context.write("value");
        });
        Task task = newTask();

        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec(), null, null);

        assertTrue(storage.awaitTerminal());
        List<String> codes = storage.listEvents(task.getId(), 0, 100).stream()
                .map(TaskEvent::getCode)
                .toList();
        assertTrue(codes.indexOf(TaskEventCode.ARTIFACT_PREPARED.name())
                < codes.indexOf(TaskEventCode.ARTIFACT_PUBLISHED.name()));
        assertTrue(codes.indexOf(TaskEventCode.ARTIFACT_PUBLISHED.name())
                < codes.indexOf(TaskEventCode.TASK_SUCCEEDED.name()));
        Files.deleteIfExists(Path.of(storage.get(task.getId()).orElseThrow().getArtifactId()));
    }

    private LocalTaskManager manager(TestTaskStorage storage, TestExecution execution) {
        return manager(storage, execution, emptyExtensionManager());
    }

    private LocalTaskManager manager(TestTaskStorage storage, TestExecution execution,
            TaskExtensionManager extensionManager) {
        TaskExecutor<ExportTaskSpec> executor = new TaskExecutor<>() {
            @Override
            public String taskType() {
                return TaskType.QUERY_RESULT_EXPORT.name();
            }

            @Override
            public Class<ExportTaskSpec> specType() {
                return ExportTaskSpec.class;
            }

            @Override
            public void execute(ExportTaskSpec spec, TaskExecutionContext context) {
                execution.execute(spec, context);
            }
        };
        return new LocalTaskManager(storage, new TaskExecutorRegistry(List.of(executor)), new ArtifactService(),
                new ConnectionContextConverter(), extensionManager, 1, 4);
    }

    private TaskExtensionManager emptyExtensionManager() {
        return new TaskExtensionManager(List.of(), List.of());
    }

    private Task newTask() {
        return Task.builder()
                .type(TaskType.QUERY_RESULT_EXPORT.name())
                .name("Export result")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).build())
                .build();
    }

    private ExportTaskSpec spec() {
        return ExportTaskSpec.builder()
                .taskType(TaskType.QUERY_RESULT_EXPORT.name())
                .taskName("Export result")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).build())
                .build();
    }

    private TaskEvent event(String code) {
        return TaskEvent.builder()
                .level(TaskEventLevel.INFO.name())
                .code(code)
                .message(code)
                .build();
    }

    @FunctionalInterface
    private interface TestExecution {
        void execute(ExportTaskSpec spec, TaskExecutionContext context);
    }

    private static final class TestTaskStorage implements TaskStorage {

        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, Task> tasks = new LinkedHashMap<>();
        private final Map<Long, List<TaskEvent>> events = new LinkedHashMap<>();
        private final CountDownLatch terminal = new CountDownLatch(1);
        private int terminalTransitions;
        private CountDownLatch createPaused;
        private CountDownLatch resumeCreate;

        @Override
        public synchronized Task create(Task task, TaskEvent createdEvent) {
            task.setId(ids.incrementAndGet());
            task.setStatus(TaskStatus.PENDING.name());
            task.setProgress(0);
            task.setCreatedAt(new Date());
            tasks.put(task.getId(), task);
            createdEvent.setTaskId(task.getId());
            appendEvent(createdEvent);
            if (createPaused != null) {
                createPaused.countDown();
                try {
                    resumeCreate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while pausing task creation", e);
                } finally {
                    createPaused = null;
                    resumeCreate = null;
                }
            }
            return task;
        }

        @Override
        public synchronized Optional<Task> get(Long taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public synchronized PageResponse<Task> list(TaskQuery query) {
            List<Task> data = tasks.values().stream()
                    .sorted(Comparator.comparing(Task::getId).reversed())
                    .toList();
            return PageResponse.of(data, (long) data.size(), 1, data.size());
        }

        @Override
        public synchronized boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            Task task = tasks.get(taskId);
            if (task == null || !expectedStatus.equals(task.getStatus()) || TaskStatus.isTerminal(task.getStatus())) {
                return false;
            }
            task.setStatus(targetStatus);
            if (TaskStatus.SUCCESS.name().equals(targetStatus)) {
                task.setProgress(100);
            }
            if (patch != null) {
                task.setStage(patch.getStage());
                task.setProgressMessage(patch.getProgressMessage());
                task.setErrorCode(patch.getErrorCode());
                task.setErrorMessage(patch.getErrorMessage());
                task.setArtifactId(patch.getArtifactId());
                task.setStartedAt(patch.getStartedAt());
                task.setFinishedAt(patch.getFinishedAt());
            }
            lifecycleEvent.setTaskId(taskId);
            appendEvent(lifecycleEvent);
            if (TaskStatus.isTerminal(targetStatus)) {
                terminalTransitions++;
                terminal.countDown();
            }
            return true;
        }

        @Override
        public synchronized boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            Task task = tasks.get(taskId);
            if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
                return false;
            }
            task.setProgress(progress.getProgress());
            task.setStage(progress.getStage());
            task.setProgressMessage(progress.getMessage());
            return true;
        }

        @Override
        public synchronized TaskEvent appendEvent(TaskEvent event) {
            List<TaskEvent> taskEvents = events.computeIfAbsent(event.getTaskId(), ignored -> new ArrayList<>());
            event.setSequence((long) taskEvents.size() + 1L);
            taskEvents.add(event);
            return event;
        }

        @Override
        public synchronized List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            return events.getOrDefault(taskId, List.of()).stream()
                    .filter(event -> event.getSequence() > afterSequence)
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            List<TaskEvent> filtered = events.getOrDefault(taskId, List.of()).stream()
                    .filter(event -> beforeSequence == null || event.getSequence() < beforeSequence)
                    .toList();
            return filtered.subList(Math.max(0, filtered.size() - limit), filtered.size());
        }

        @Override
        public synchronized List<Task> listNonTerminalTasks() {
            return tasks.values().stream()
                    .filter(task -> !TaskStatus.isTerminal(task.getStatus()))
                    .toList();
        }

        @Override
        public synchronized List<Task> listTasksForRecovery() {
            return List.copyOf(tasks.values());
        }

        @Override
        public synchronized boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            Task task = tasks.get(taskId);
            if (task == null || !TaskStatus.isTerminal(task.getStatus())) {
                return false;
            }
            tasks.remove(taskId);
            events.remove(taskId);
            commitAction.run();
            return true;
        }

        boolean awaitTerminal() throws InterruptedException {
            return terminal.await(5, TimeUnit.SECONDS);
        }

        synchronized int terminalTransitionCount() {
            return terminalTransitions;
        }

        synchronized void pauseNextCreate(CountDownLatch paused, CountDownLatch resume) {
            createPaused = paused;
            resumeCreate = resume;
        }
    }
}
