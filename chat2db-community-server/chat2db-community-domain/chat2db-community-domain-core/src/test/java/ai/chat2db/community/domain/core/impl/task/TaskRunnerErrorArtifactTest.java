package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.domain.core.impl.task.extension.TaskExtensionManager;
import ai.chat2db.community.domain.core.impl.task.imports.ImportErrorFileWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the task layer does with the rejected-rows file of a failed run: a resumable import keeps it
 * (published and attached to the task), a task that cannot be continued keeps the historical
 * cleanup, and both keep their terminal status.
 */
class TaskRunnerErrorArtifactTest {

    private static final String ERROR_CSV = "rowNumber,reason,content\n3,rejected,1|ok\n";

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
    void failedResumableImportPublishesAndAttachesItsErrorArtifact() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        ImportTaskSpec spec = importSpec();
        taskManager = manager(storage, TaskType.DATA_FILE_IMPORT.name(), ImportTaskSpec.class,
                (importSpec, context) -> stageErrorFileAndFail(context));

        Task task = newTask();
        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec, null, null);

        assertTrue(storage.awaitTerminal());
        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertNotNull(failed.getErrorArtifactId(), "a resumable import keeps its rejected-rows file");
        Path published = Path.of(failed.getErrorArtifactId());
        assertTrue(Files.isRegularFile(published));
        assertEquals(ERROR_CSV, Files.readString(published));
        assertTrue(storage.listEvents(task.getId(), 0L, 100).stream()
                        .anyMatch(event -> TaskEventCode.ERROR_ARTIFACT_PUBLISHED.name().equals(event.getCode())),
                "publishing must be visible in the task log");
    }

    @Test
    void failedExportDeletesTheUnusedErrorArtifact() throws Exception {
        TestTaskStorage storage = new TestTaskStorage();
        java.util.concurrent.atomic.AtomicReference<Path> staged = new java.util.concurrent.atomic.AtomicReference<>();
        ExportTaskSpec spec = ExportTaskSpec.builder()
                .taskType(TaskType.QUERY_RESULT_EXPORT.name())
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("T").build())
                .build();
        taskManager = manager(storage, TaskType.QUERY_RESULT_EXPORT.name(), ExportTaskSpec.class,
                (exportSpec, context) -> {
                    ArtifactDraft draft = context.createErrorArtifact(tempDirectory.toString(),
                            ImportErrorFileWriter.FILE_NAME, "text/csv");
                    staged.set(draft.getTemporaryFile().toPath());
                    try {
                        Files.writeString(staged.get(), ERROR_CSV);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), "Export failed");
                });

        Task task = newTask();
        taskManager.submit(task, event(TaskEventCode.TASK_CREATED.name()), spec, null, null);

        assertTrue(storage.awaitTerminal());
        Task failed = storage.get(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertNull(failed.getErrorArtifactId());
        assertTrue(Files.notExists(staged.get()), "a task that cannot be continued keeps the old cleanup");
    }

    private ArtifactDraft stageErrorFileAndFail(TaskExecutionContext context) {
        try {
            ArtifactDraft draft = context.createErrorArtifact(tempDirectory.toString(),
                    ImportErrorFileWriter.FILE_NAME, "text/csv");
            Files.writeString(draft.getTemporaryFile().toPath(), ERROR_CSV);
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), "Import failed");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ImportTaskSpec importSpec() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("input.csv"), "ROW_ID\n1\n");
        return ImportTaskSpec.builder()
                .taskType(TaskType.DATA_FILE_IMPORT.name())
                .sourceFile(source.toString())
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("T").build())
                .build();
    }

    private <S extends TaskSpec> LocalTaskManager manager(TestTaskStorage storage, String taskType,
            Class<S> specType, BiConsumer<S, TaskExecutionContext> execution) {
        TaskExecutor<S> executor = new TaskExecutor<>() {
            @Override
            public String taskType() {
                return taskType;
            }

            @Override
            public Class<S> specType() {
                return specType;
            }

            @Override
            public void execute(S spec, TaskExecutionContext context) {
                execution.accept(spec, context);
            }
        };
        return new LocalTaskManager(storage, new TaskExecutorRegistry(List.of(executor)), new ArtifactServiceImpl(),
                new ConnectionContextConverter(), new TaskExtensionManager(List.of(), List.of()), 1, 4);
    }

    private Task newTask() {
        return Task.builder().type(TaskType.DATA_FILE_IMPORT.name()).name("import").build();
    }

    private static TaskEvent event(String code) {
        return TaskEvent.builder().level("INFO").code(code).message(code).build();
    }

    private static final class TestTaskStorage implements TaskStorage {

        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, Task> tasks = new LinkedHashMap<>();
        private final Map<Long, List<TaskEvent>> events = new LinkedHashMap<>();
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public synchronized Task create(Task task, TaskEvent createdEvent) {
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
            List<Task> data = tasks.values().stream()
                    .sorted(Comparator.comparing(Task::getId).reversed())
                    .toList();
            return PageResponse.of(data, (long) data.size(), 1, data.size());
        }

        @Override
        public synchronized boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            Task task = tasks.get(taskId);
            if (task == null || !expectedStatus.equals(task.getStatus())) {
                return false;
            }
            task.setStatus(targetStatus);
            if (patch != null) {
                task.setStage(patch.getStage());
                task.setProgressMessage(patch.getProgressMessage());
                task.setErrorCode(patch.getErrorCode());
                task.setErrorMessage(patch.getErrorMessage());
                task.setFinishedAt(patch.getFinishedAt());
            }
            lifecycleEvent.setTaskId(taskId);
            appendEvent(lifecycleEvent);
            if (TaskStatus.isTerminal(targetStatus)) {
                terminal.countDown();
            }
            return true;
        }

        @Override
        public synchronized boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
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
                    .filter(event -> event.getSequence() > afterSequence).limit(limit).toList();
        }

        @Override
        public synchronized List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            return List.of();
        }

        @Override
        public synchronized List<Task> listNonTerminalTasks() {
            return List.of();
        }

        @Override
        public synchronized boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            return false;
        }

        @Override
        public synchronized boolean attachErrorArtifact(Long taskId, String errorArtifactId) {
            Task task = tasks.get(taskId);
            if (task == null) {
                return false;
            }
            task.setErrorArtifactId(errorArtifactId);
            return true;
        }

        boolean awaitTerminal() throws InterruptedException {
            return terminal.await(5, TimeUnit.SECONDS);
        }
    }
}
