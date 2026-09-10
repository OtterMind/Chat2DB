package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.ArtifactService;
import ai.chat2db.community.domain.api.service.task.TaskDeletionService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskDeletionServiceImplTest {
    @TempDir
    Path tempDirectory;

    private File journalFile() {
        return tempDirectory.resolve("task-artifact-deletions.json").toFile();
    }

    @Test
    void springResolvesServicesThroughTheirInterfaces() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("spring.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TaskStorage.class, () -> storage);
            context.register(ArtifactServiceImpl.class, TaskDeletionServiceImpl.class);
            context.refresh();
            ArtifactService files = context.getBean(ArtifactService.class);
            TaskDeletionService deletions = context.getBean(TaskDeletionService.class);
            assertEquals(ArtifactServiceImpl.class, files.getClass());
            deletions.delete(task(1L, artifact));
        }
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
    }

    @Test
    void successfulDeletionRemovesTaskArtifactAndArrayEntry() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertEquals("[]", Files.readString(journalFile().toPath()));
    }

    @Test
    void tasksWithoutArtifactsAlsoUseTheDeletionQueue() throws IOException {
        RecordingTaskStorage storage = storage(1L, null);
        storage.beforeDelete = () -> assertEquals(1, queue().get(0).getAttempts());
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertEquals("[]", Files.readString(journalFile().toPath()));
    }

    @Test
    void storageFailureLeavesStagedFileAndRestartCompletesDeletion() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        RecordingTaskStorage storage = storage(1L, artifact);
        storage.failDeletion = true;
        TaskServiceImpl service = tasks(storage);
        assertThrows(BusinessException.class, () -> service.delete(1L));
        var pending = queue().get(0);
        assertEquals(1, pending.getAttempts());
        assertTrue(pending.getLastError().contains("storage unavailable"));
        assertFalse(Files.exists(artifact));
        assertEquals("old export", Files.readString(Path.of(pending.getStagedPath())));
        Files.writeString(artifact, "new export");
        var download = service.resolveArtifact(1L);
        assertEquals("export.csv", download.getFileName());
        assertEquals("old export", Files.readString(Path.of(URI.create(download.getFileUri()))));

        storage.failDeletion = false;
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(storage.get(1L).isEmpty());
        assertEquals("new export", Files.readString(artifact));
        assertFalse(Files.exists(Path.of(pending.getStagedPath())));
        assertTrue(queue().isEmpty());
    }

    @Test
    void restartResumesIntentWrittenBeforeTheFileWasStaged() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        var pending = intent(1L, artifact, 1);
        writeQueue(pending);
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertFalse(Files.exists(artifact));
        assertFalse(Files.exists(Path.of(pending.getStagedPath())));
        assertTrue(storage.get(1L).isEmpty());
        assertTrue(queue().isEmpty());
    }

    @Test
    void restartAfterTaskRemovalOnlyDeletesTheUniqueStagedFile() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "new export");
        var pending = intent(1L, artifact, 1);
        Files.writeString(Path.of(pending.getStagedPath()), "old export");
        writeQueue(pending);
        tasks(new RecordingTaskStorage()).recoverInterruptedArtifactDeletions();
        assertEquals("new export", Files.readString(artifact));
        assertFalse(Files.exists(Path.of(pending.getStagedPath())));
        assertTrue(queue().isEmpty());
    }

    @Test
    void missingArtifactIsAlreadyDeleted() throws IOException {
        Path missing = tempDirectory.resolve("missing.csv");
        RecordingTaskStorage storage = storage(1L, missing);
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertTrue(queue().isEmpty());
    }

    @Test
    void repeatedStartupFailuresStopAtTheAttemptLimitAndKeepTheirError() throws IOException {
        Path directory = Files.createDirectory(tempDirectory.resolve("not-a-file"));
        Files.writeString(directory.resolve("child"), "keep");
        RecordingTaskStorage storage = storage(1L, directory);
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        for (int startup = 0; startup < 5; startup++) {
            tasks(storage).recoverInterruptedArtifactDeletions();
        }
        var failed = queue().get(0);
        assertEquals(TaskDeletionServiceImpl.MAX_DELETION_ATTEMPTS, failed.getAttempts());
        assertTrue(failed.getLastError().contains("not a regular file"));
        assertTrue(storage.get(1L).isPresent());
        assertEquals("keep", Files.readString(directory.resolve("child")));
    }

    @Test
    void anExplicitDeleteCanRetryAnExhaustedEntryAfterTheProblemIsFixed() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        writeQueue(intent(1L, artifact, TaskDeletionServiceImpl.MAX_DELETION_ATTEMPTS));
        tasks(storage).delete(1L);
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertTrue(queue().isEmpty());
    }

    @Test
    void undeletableStagedFileKeepsItsErrorWithoutRestoringTheRemovedTask() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "new export");
        var pending = intent(1L, artifact, 0);
        Path staged = Files.createDirectory(Path.of(pending.getStagedPath()));
        Files.writeString(staged.resolve("child"), "keep");
        writeQueue(pending);
        RecordingTaskStorage storage = new RecordingTaskStorage();
        for (int startup = 0; startup < 5; startup++) {
            tasks(storage).recoverInterruptedArtifactDeletions();
        }
        assertTrue(storage.get(1L).isEmpty());
        assertEquals(TaskDeletionServiceImpl.MAX_DELETION_ATTEMPTS, queue().get(0).getAttempts());
        assertTrue(queue().get(0).getLastError().contains("DirectoryNotEmptyException"));
        assertEquals("new export", Files.readString(artifact));
        assertEquals("keep", Files.readString(staged.resolve("child")));
    }

    @Test
    void oneExhaustedEntryDoesNotBlockOtherPendingDeletions() throws IOException {
        Path first = Files.writeString(tempDirectory.resolve("first.csv"), "first");
        Path second = Files.writeString(tempDirectory.resolve("second.csv"), "second");
        RecordingTaskStorage storage = storage(1L, first);
        storage.tasks.put(2L, task(2L, second));
        writeQueue(intent(1L, first, TaskDeletionServiceImpl.MAX_DELETION_ATTEMPTS), intent(2L, second, 0));
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(Files.exists(first));
        assertTrue(storage.get(1L).isPresent());
        assertFalse(Files.exists(second));
        assertTrue(storage.get(2L).isEmpty());
        assertEquals(List.of(1L), queue().stream().map(PendingTaskDeletion::getTaskId).toList());
    }

    @Test
    void alreadyCompletedDeletionIsRemovedEvenAtTheAttemptLimit() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "new export");
        writeQueue(intent(1L, artifact, TaskDeletionServiceImpl.MAX_DELETION_ATTEMPTS));
        tasks(new RecordingTaskStorage()).recoverInterruptedArtifactDeletions();
        assertTrue(queue().isEmpty());
        assertEquals("new export", Files.readString(artifact));
    }

    @Test
    void queueWriteFailurePreventsAnyDeletion() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        Files.createDirectory(tempDirectory.resolve(journalFile().getName() + ".part"));
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
        assertFalse(journalFile().exists());
    }

    @Test
    void queueCleanupFailureDoesNotRestoreADeletedTask() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        RecordingTaskStorage storage = storage(1L, artifact);
        Path blockedWrite = tempDirectory.resolve(journalFile().getName() + ".part");
        storage.afterDelete = () -> assertDoesNotThrow(() -> Files.createDirectory(blockedWrite));
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertEquals(1, queue().size());
        Files.delete(blockedWrite);
        Files.writeString(artifact, "new export");
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertEquals("new export", Files.readString(artifact));
        assertTrue(queue().isEmpty());
    }

    @Test
    void corruptQueueIsPreservedAndPreventsNewDestructiveWork() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        Files.writeString(journalFile().toPath(), "broken JSON");
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertEquals("broken JSON", Files.readString(journalFile().toPath()));
        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void concurrentFailedDeletesKeepBothEntriesInTheSameArray() throws Exception {
        Path first = Files.writeString(tempDirectory.resolve("first.csv"), "first");
        Path second = Files.writeString(tempDirectory.resolve("second.csv"), "second");
        RecordingTaskStorage storage = storage(1L, first);
        storage.tasks.put(2L, task(2L, second));
        storage.failDeletion = true;
        TaskServiceImpl service = tasks(storage);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var jobs = List.of(1L, 2L).stream().map(id -> executor.submit(() -> {
                start.await();
                assertThrows(BusinessException.class, () -> service.delete(id));
                return null;
            })).toList();
            start.countDown();
            for (var job : jobs) {
                job.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(List.of(1L, 2L), queue().stream().map(PendingTaskDeletion::getTaskId).sorted().toList());
        assertTrue(queue().stream().allMatch(entry -> entry.getAttempts() == 1 && entry.getLastError() != null));
        storage.failDeletion = false;
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(queue().isEmpty());
        assertTrue(storage.tasks.isEmpty());
    }

    @Test
    void activeTasksAndUnownedTasksNeverEnterTheDeletionQueue() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        storage.tasks.get(1L).setStatus(TaskStatus.RUNNING.name());
        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));
        storage.tasks.get(1L).setStatus(TaskStatus.SUCCESS.name());
        storage.tasks.get(1L).setUserId(123L);
        assertThrows(DataNotFoundException.class, () -> tasks(storage).delete(1L));
        assertFalse(journalFile().exists());
        assertEquals("value", Files.readString(artifact));
    }

    @Test
    void concurrentDeletionOfTheSameTaskCommitsOnlyOnce() throws Exception {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, artifact);
        AtomicInteger commits = new AtomicInteger();
        storage.afterDelete = commits::incrementAndGet;
        TaskServiceImpl service = tasks(storage);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var jobs = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                start.await();
                try {
                    service.delete(1L);
                    return true;
                } catch (DataNotFoundException alreadyDeleted) {
                    return false;
                }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var job : jobs) {
                successes += job.get() ? 1 : 0;
            }
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, commits.get());
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        assertTrue(queue().isEmpty());
    }

    @Test
    void abruptTerminationAfterStagingLeavesDurableWorkForRestart() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        RecordingTaskStorage storage = storage(1L, artifact);
        storage.beforeDelete = () -> { throw new AssertionError("simulated process termination"); };

        assertThrows(AssertionError.class, () -> tasks(storage).delete(1L));

        PendingTaskDeletion pending = queue().get(0);
        assertEquals(1, pending.getAttempts());
        assertTrue(storage.get(1L).isPresent());
        assertFalse(Files.exists(artifact));
        assertEquals("old export", Files.readString(pending.stagedFile()));
        storage.beforeDelete = () -> {};
        Files.writeString(artifact, "new export");
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(pending.stagedFile()));
        assertEquals("new export", Files.readString(artifact));
        assertTrue(queue().isEmpty());
    }

    @Test
    void simultaneousStorageAndQueueWriteFailureStillLeavesRecoverableIntent() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "old export");
        RecordingTaskStorage storage = storage(1L, artifact);
        Path blockedWrite = tempDirectory.resolve(journalFile().getName() + ".part");
        storage.beforeDelete = () -> assertDoesNotThrow(() -> Files.createDirectory(blockedWrite));
        storage.failDeletion = true;

        assertThrows(BusinessException.class, () -> tasks(storage).delete(1L));

        PendingTaskDeletion pending = queue().get(0);
        assertEquals(1, pending.getAttempts());
        assertTrue(storage.get(1L).isPresent());
        assertEquals("old export", Files.readString(pending.stagedFile()));
        Files.delete(blockedWrite);
        storage.beforeDelete = () -> {};
        storage.failDeletion = false;
        Files.writeString(artifact, "new export");
        tasks(storage).recoverInterruptedArtifactDeletions();
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(pending.stagedFile()));
        assertEquals("new export", Files.readString(artifact));
        assertTrue(queue().isEmpty());
    }

    @Test
    void aFailedAttemptDoesNotPreventTheNextEntryFromCompleting() throws IOException {
        Path invalidArtifact = Files.createDirectory(tempDirectory.resolve("not-a-file"));
        Path validArtifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = storage(1L, invalidArtifact);
        storage.tasks.put(2L, task(2L, validArtifact));
        writeQueue(intent(1L, invalidArtifact, 0), intent(2L, validArtifact, 0));

        tasks(storage).recoverInterruptedArtifactDeletions();

        assertEquals(1, queue().size());
        assertEquals(1L, queue().get(0).getTaskId());
        assertEquals(1, queue().get(0).getAttempts());
        assertTrue(queue().get(0).getLastError().contains("not a regular file"));
        assertTrue(storage.get(1L).isPresent());
        assertTrue(storage.get(2L).isEmpty());
        assertFalse(Files.exists(validArtifact));
    }

    private TaskServiceImpl tasks(RecordingTaskStorage storage) {
        return new TaskServiceImpl(storage, null, new TaskDeletionServiceImpl(storage, new ArtifactServiceImpl(), journalFile()));
    }

    private Task task(Long id, Path artifact) {
        return Task.builder().id(id).status(TaskStatus.SUCCESS.name())
                .artifactId(artifact == null ? null : artifact.toString()).build();
    }

    private RecordingTaskStorage storage(Long id, Path artifact) {
        RecordingTaskStorage storage = new RecordingTaskStorage();
        storage.tasks.put(id, task(id, artifact));
        return storage;
    }

    private PendingTaskDeletion intent(Long id, Path artifact, int attempts) {
        return new PendingTaskDeletion(id, artifact.toString(),
                artifact.resolveSibling("." + artifact.getFileName() + ".task-delete-" + id).toString(), attempts, null);
    }

    private void writeQueue(PendingTaskDeletion... pending) throws IOException {
        Files.writeString(journalFile().toPath(), JSON.toJSONString(List.of(pending)));
    }

    private List<PendingTaskDeletion> queue() {
        return assertDoesNotThrow(() -> JSON.parseArray(Files.readString(journalFile().toPath()),
                PendingTaskDeletion.class));
    }

    private static final class RecordingTaskStorage implements TaskStorage {
        private final Map<Long, Task> tasks = new LinkedHashMap<>();
        private boolean failDeletion;
        private Runnable beforeDelete = () -> {};
        private Runnable afterDelete = () -> {};

        @Override
        public synchronized Optional<Task> get(Long id) {
            return Optional.ofNullable(tasks.get(id));
        }

        @Override
        public synchronized boolean deleteTerminalTask(Long id, Runnable commitAction) {
            beforeDelete.run();
            if (failDeletion) {
                throw new IllegalStateException("storage unavailable");
            }
            Task task = tasks.get(id);
            if (task == null || !TaskStatus.isTerminal(task.getStatus())) {
                return false;
            }
            tasks.remove(id);
            afterDelete.run();
            if (commitAction != null) {
                commitAction.run();
            }
            return true;
        }

        @Override
        public Task create(Task task, TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean compareAndSetStatus(Long id, String expected, String target,
                TaskStatusPatch patch, TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateProgressIfRunning(Long id, TaskProgress progress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEvents(Long id, long after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long id, Long before, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            throw new UnsupportedOperationException();
        }
    }
}
