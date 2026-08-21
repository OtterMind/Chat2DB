package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.task.extension.TaskOperation;
import ai.chat2db.community.domain.api.model.task.extension.TaskSubmissionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.domain.core.impl.task.extension.TaskExtensionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutorRegistryTest {

    private LocalTaskManager taskManager;

    @AfterEach
    void tearDown() {
        if (taskManager != null) {
            taskManager.shutdown();
        }
    }

    @Test
    void duplicateTaskTypeRegistrationIsRejected() {
        TaskExecutor<ExportTaskSpec> first = exportExecutor(TaskType.QUERY_RESULT_EXPORT.name(),
                (spec, context) -> {});
        TaskExecutor<ExportTaskSpec> duplicate = exportExecutor(TaskType.QUERY_RESULT_EXPORT.name(),
                (spec, context) -> {});

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new TaskExecutorRegistry(List.of(first, duplicate)));

        assertTrue(exception.getMessage().contains(TaskType.QUERY_RESULT_EXPORT.name()));
    }

    @Test
    void exportSubmissionRejectsImportTaskTypeBeforePersistence() {
        RecordingTaskStorage storage = new RecordingTaskStorage();
        TaskServiceImpl taskService = taskService(storage);
        ExportTaskSpec spec = ExportTaskSpec.builder()
                .taskType(TaskType.DATA_FILE_IMPORT.name())
                .target(target())
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> taskService.submitExport(spec));

        assertTrue(exception.getMessage().contains(ImportTaskSpec.class.getSimpleName()));
        assertEquals(0, storage.createCount());
    }

    @Test
    void importSubmissionRejectsExportTaskTypeBeforePersistence() {
        RecordingTaskStorage storage = new RecordingTaskStorage();
        TaskServiceImpl taskService = taskService(storage);
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType(TaskType.QUERY_RESULT_EXPORT.name())
                .target(target())
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> taskService.submitImport(spec));

        assertTrue(exception.getMessage().contains(ExportTaskSpec.class.getSimpleName()));
        assertEquals(0, storage.createCount());
    }

    @Test
    void artifactPublishFailureDoesNotMarkTaskSuccessful(@TempDir Path tempDirectory) throws IOException {
        RecordingTaskStorage storage = new RecordingTaskStorage();
        Task task = storage.create(Task.builder()
                        .type(TaskType.QUERY_RESULT_EXPORT.name())
                        .name("Export result")
                        .target(target())
                        .build(),
                TaskEvent.builder().message("Task created").build());
        RunningTask runningTask = new RunningTask(task.getId());
        RunningTaskRegistry runningTaskRegistry = new RunningTaskRegistry();
        runningTaskRegistry.register(runningTask);
        AtomicReference<ArtifactDraft> draftReference = new AtomicReference<>();
        TaskExecutor<ExportTaskSpec> executor = exportExecutor(TaskType.QUERY_RESULT_EXPORT.name(),
                (spec, context) -> {
                    ArtifactDraft draft = context.createArtifact(tempDirectory.toString(), "result.csv", "text/csv");
                    draftReference.set(draft);
                    context.write("value");
                });
        ArtifactService failingArtifactService = new ArtifactService() {
            @Override
            String publish(ArtifactDraft ignored) {
                throw new IllegalStateException("Publish failed");
            }
        };
        TaskRunner<ExportTaskSpec> runner = new TaskRunner<>(
                new TaskSubmission<>(task.getId(), exportSpec(), null, null,
                        new TaskSubmissionContext(task.getId(), TaskType.QUERY_RESULT_EXPORT, null,
                                null, null, List.of(), TaskOperation.EXPORT).toExecutionContext()),
                runningTask, runningTaskRegistry, storage, executor, failingArtifactService,
                emptyExtensionManager());

        runner.run();

        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertEquals(TaskErrorCode.ARTIFACT_PUBLISH_FAILED.name(), failed.getErrorCode());
        assertFalse(storage.statusTransitions().contains(TaskStatus.SUCCESS.name()));
        ArtifactDraft draft = draftReference.get();
        assertFalse(Files.exists(draft.getTemporaryFile().toPath()));
        assertFalse(Files.exists(draft.getTargetFile().toPath()));
    }

    private TaskServiceImpl taskService(RecordingTaskStorage storage) {
        TaskExecutorRegistry registry = new TaskExecutorRegistry(List.of(
                exportExecutor(TaskType.QUERY_RESULT_EXPORT.name(),
                        (spec, context) -> {}),
                importExecutor(TaskType.DATA_FILE_IMPORT.name())));
        taskManager = new LocalTaskManager(storage, registry, new ArtifactService(),
                new ConnectionContextConverter(), emptyExtensionManager(), 1, 1);
        return new TaskServiceImpl(storage, taskManager, new ArtifactService());
    }

    private TaskExtensionManager emptyExtensionManager() {
        return new TaskExtensionManager(List.of(), List.of());
    }

    private TaskExecutor<ExportTaskSpec> exportExecutor(String taskType,
            BiConsumer<ExportTaskSpec, TaskExecutionContext> execution) {
        return new TaskExecutor<>() {
            @Override
            public String taskType() {
                return taskType;
            }

            @Override
            public Class<ExportTaskSpec> specType() {
                return ExportTaskSpec.class;
            }

            @Override
            public void execute(ExportTaskSpec spec, TaskExecutionContext context) {
                execution.accept(spec, context);
            }
        };
    }

    private TaskExecutor<ImportTaskSpec> importExecutor(String taskType) {
        return new TaskExecutor<>() {
            @Override
            public String taskType() {
                return taskType;
            }

            @Override
            public Class<ImportTaskSpec> specType() {
                return ImportTaskSpec.class;
            }

            @Override
            public void execute(ImportTaskSpec spec, TaskExecutionContext context) {
            }
        };
    }

    private ExportTaskSpec exportSpec() {
        return ExportTaskSpec.builder()
                .taskType(TaskType.QUERY_RESULT_EXPORT.name())
                .taskName("Export result")
                .target(target())
                .build();
    }

    private TaskTargetSnapshot target() {
        return TaskTargetSnapshot.builder().dataSourceId(1L).build();
    }

    private static final class RecordingTaskStorage implements TaskStorage {

        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, Task> tasks = new LinkedHashMap<>();
        private final Map<Long, List<TaskEvent>> events = new LinkedHashMap<>();
        private final List<String> statusTransitions = new ArrayList<>();
        private int createCount;

        @Override
        public synchronized Task create(Task task, TaskEvent createdEvent) {
            createCount++;
            task.setId(ids.incrementAndGet());
            task.setStatus(TaskStatus.PENDING.name());
            task.setProgress(0);
            task.setCreatedAt(new Date());
            tasks.put(task.getId(), task);
            createdEvent.setTaskId(task.getId());
            appendEvent(createdEvent);
            return task;
        }

        @Override
        public synchronized Optional<Task> get(Long taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public synchronized PageResponse<Task> list(TaskQuery query) {
            return PageResponse.of(new ArrayList<>(tasks.values()), (long) tasks.size(), 1, tasks.size());
        }

        @Override
        public synchronized boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            Task task = tasks.get(taskId);
            if (task == null || !expectedStatus.equals(task.getStatus()) || TaskStatus.isTerminal(task.getStatus())) {
                return false;
            }
            task.setStatus(targetStatus);
            statusTransitions.add(targetStatus);
            if (patch != null) {
                task.setProgress(patch.getProgress());
                task.setStage(patch.getStage());
                task.setProgressMessage(patch.getProgressMessage());
                task.setErrorCode(patch.getErrorCode());
                task.setErrorMessage(patch.getErrorMessage());
                task.setArtifactId(patch.getArtifactId());
                task.setStartedAt(patch.getStartedAt());
                task.setFinishedAt(patch.getFinishedAt());
                task.setUpdatedAt(patch.getUpdatedAt());
            }
            lifecycleEvent.setTaskId(taskId);
            appendEvent(lifecycleEvent);
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

        synchronized int createCount() {
            return createCount;
        }

        synchronized List<String> statusTransitions() {
            return List.copyOf(statusTransitions);
        }
    }
}
