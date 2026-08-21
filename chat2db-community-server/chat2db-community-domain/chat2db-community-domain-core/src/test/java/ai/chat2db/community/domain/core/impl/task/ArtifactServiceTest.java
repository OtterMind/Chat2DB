package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void concurrentDraftsReserveDifferentTargetsAndPublishIndependently() throws IOException {
        ArtifactService service = new ArtifactService();
        var first = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        var second = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertNotEquals(first.getTargetFile(), second.getTargetFile());
        Files.writeString(first.getTemporaryFile().toPath(), "first");
        Files.writeString(second.getTemporaryFile().toPath(), "second");

        String firstArtifact = service.publish(first);
        String secondArtifact = service.publish(second);

        assertEquals("first", Files.readString(Path.of(firstArtifact)));
        assertEquals("second", Files.readString(Path.of(secondArtifact)));
        service.deletePublished(firstArtifact);
        assertFalse(Files.exists(Path.of(firstArtifact)));
        assertTrue(Files.exists(Path.of(secondArtifact)));
    }

    @Test
    void failedPublicationReleasesReservedTarget() {
        ArtifactService service = new ArtifactService();
        var failed = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");

        assertThrows(IllegalStateException.class, () -> service.publish(failed));

        var replacement = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertEquals(failed.getTargetFile(), replacement.getTargetFile());
        service.deleteDraft(replacement);
    }

    @Test
    void taskDeletionRemovesPublishedArtifactBeforeTaskRecord() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("export.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());

        new TaskServiceImpl(storage, null, new ArtifactService()).delete(1L);

        assertFalse(Files.exists(artifact));
        assertTrue(storage.deleted);
        assertTrue(storage.get(1L).isEmpty());
    }

    @Test
    void artifactDeletionFailurePreservesTaskRecord() throws IOException {
        Path nonEmptyDirectory = Files.createDirectory(tempDirectory.resolve("artifact-directory"));
        Files.writeString(nonEmptyDirectory.resolve("child"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(nonEmptyDirectory.toString())
                .build());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new TaskServiceImpl(storage, null, new ArtifactService()).delete(1L));

        assertEquals(TaskConstants.DELETE_ARTIFACT_FAILED_MESSAGE_CODE, exception.getCode());
        assertFalse(storage.deleted);
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void taskStorageDeletionFailureRestoresPublishedArtifact() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("recover.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());
        storage.failDeletion = true;

        assertThrows(IllegalStateException.class,
                () -> new TaskServiceImpl(storage, null, new ArtifactService()).delete(1L));

        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void artifactCommitFailureRestoresTaskAndPublishedArtifact() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("commit-failure.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());
        ArtifactService artifactService = new ArtifactService() {
            @Override
            void commitPublishedDeletion(PublishedArtifactDeletion deletion) {
                throw new IllegalStateException("Could not commit artifact deletion");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new TaskServiceImpl(storage, null, artifactService).delete(1L));

        assertEquals("value", Files.readString(artifact));
        assertTrue(storage.get(1L).isPresent());
    }

    @Test
    void concurrentDeletionCannotRestoreAnOrphanArtifact() throws Exception {
        Path artifact = Files.writeString(tempDirectory.resolve("concurrent.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.SUCCESS.name())
                .artifactId(artifact.toString())
                .build());
        TaskServiceImpl service = new TaskServiceImpl(storage, null, new ArtifactService());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger deleted = new AtomicInteger();
        AtomicInteger alreadyDeleted = new AtomicInteger();
        try {
            Future<?> first = executor.submit(() -> {
                start.await();
                try {
                    service.delete(1L);
                    deleted.incrementAndGet();
                } catch (DataNotFoundException ignored) {
                    alreadyDeleted.incrementAndGet();
                }
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                try {
                    service.delete(1L);
                    deleted.incrementAndGet();
                } catch (DataNotFoundException ignored) {
                    alreadyDeleted.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, deleted.get());
        assertEquals(1, alreadyDeleted.get());
        assertTrue(storage.get(1L).isEmpty());
        assertFalse(Files.exists(artifact));
        try (var files = Files.list(tempDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".task-delete-")));
        }
    }

    @Test
    void activeTaskDeletionIsRejectedBeforeArtifactDeletion() throws IOException {
        Path artifact = Files.writeString(tempDirectory.resolve("running.csv"), "value");
        RecordingTaskStorage storage = new RecordingTaskStorage(Task.builder()
                .id(1L)
                .status(TaskStatus.RUNNING.name())
                .artifactId(artifact.toString())
                .build());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new TaskServiceImpl(storage, null, new ArtifactService()).delete(1L));

        assertEquals(TaskConstants.DELETE_ACTIVE_FORBIDDEN_MESSAGE_CODE, exception.getCode());
        assertTrue(Files.exists(artifact));
        assertFalse(storage.deleted);
    }

    private static final class RecordingTaskStorage implements TaskStorage {

        private Task task;

        private boolean deleted;

        private boolean failDeletion;

        private RecordingTaskStorage(Task task) {
            this.task = task;
        }

        @Override
        public synchronized Optional<Task> get(Long taskId) {
            return Optional.ofNullable(task);
        }

        @Override
        public synchronized boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            if (failDeletion) {
                throw new IllegalStateException("Could not delete task record");
            }
            deleted = task != null && TaskStatus.isTerminal(task.getStatus());
            if (deleted) {
                Task deletedTask = task;
                task = null;
                try {
                    commitAction.run();
                } catch (RuntimeException e) {
                    task = deletedTask;
                    deleted = false;
                    throw e;
                }
            }
            return deleted;
        }

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            throw new UnsupportedOperationException();
        }
    }
}
