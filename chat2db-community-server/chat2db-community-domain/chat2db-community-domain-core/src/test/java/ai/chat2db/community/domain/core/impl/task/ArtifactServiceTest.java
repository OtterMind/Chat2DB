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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void targetCreatedAfterReservationIsPreservedAndPublicationRetries() throws IOException {
        ArtifactService service = new ArtifactService();
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path reservedTarget = draft.getTargetFile().toPath();
        Path temporary = draft.getTemporaryFile().toPath();
        byte[] generatedBytes = new byte[]{0, 1, 2, 3};
        byte[] existingBytes = new byte[]{4, 5, (byte) 0x80, (byte) 0xff};
        Files.write(temporary, generatedBytes);
        Files.write(reservedTarget, existingBytes);

        String artifactId = service.publish(draft);
        Path published = Path.of(artifactId);

        assertArrayEquals(existingBytes, Files.readAllBytes(reservedTarget));
        assertNotEquals(reservedTarget.toAbsolutePath(), published);
        assertArrayEquals(generatedBytes, Files.readAllBytes(published));
        assertFalse(Files.exists(temporary));

        service.deletePublished(artifactId);
        var retry = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertEquals(published, retry.getTargetFile().toPath().toAbsolutePath());
        service.deleteDraft(retry);
    }

    @Test
    void slowFallbackCopyNeverExposesPartialTargetAndRetriesConcurrentCollision() throws Exception {
        CountDownLatch partialStageWritten = new CountDownLatch(1);
        CountDownLatch continueCopy = new CountDownLatch(1);
        AtomicInteger linkAttempts = new AtomicInteger();
        AtomicReference<Path> forcedTarget = new AtomicReference<>();
        ArtifactService service = new ArtifactService() {
            @Override
            void createPublicationLink(Path target, Path source) throws IOException {
                if (target.equals(forcedTarget.get()) && linkAttempts.getAndIncrement() == 0) {
                    throw new IOException("force private staging");
                }
                super.createPublicationLink(target, source);
            }

            @Override
            void copyPublicationStage(Path source, FileChannel output) throws IOException {
                byte[] bytes = Files.readAllBytes(source);
                writeFully(output, ByteBuffer.wrap(bytes, 0, 2));
                output.force(true);
                partialStageWritten.countDown();
                try {
                    if (!continueCopy.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to finish the staged copy");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while staging artifact", e);
                }
                writeFully(output, ByteBuffer.wrap(bytes, 2, bytes.length - 2));
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path reservedTarget = draft.getTargetFile().toPath();
        forcedTarget.set(reservedTarget.toAbsolutePath().normalize());
        Path source = draft.getTemporaryFile().toPath();
        byte[] generatedBytes = new byte[]{0, 1, 2, 3, 4, 5};
        byte[] concurrentBytes = new byte[]{9, 8, 7};
        byte[] replacementDraftBytes = new byte[]{6, 6, 6};
        Files.write(source, generatedBytes);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> publication = executor.submit(() -> service.publish(draft));
            assertTrue(partialStageWritten.await(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(reservedTarget));
            Path privateStage = findPublicationStage();
            assertEquals(2L, Files.size(privateStage));

            Files.write(reservedTarget, concurrentBytes, StandardOpenOption.CREATE_NEW);
            Files.delete(source);
            Files.write(source, replacementDraftBytes, StandardOpenOption.CREATE_NEW);
            continueCopy.countDown();
            Path published = Path.of(publication.get(5, TimeUnit.SECONDS));

            assertArrayEquals(concurrentBytes, Files.readAllBytes(reservedTarget));
            assertNotEquals(reservedTarget.toAbsolutePath(), published);
            assertArrayEquals(generatedBytes, Files.readAllBytes(published));
            assertArrayEquals(replacementDraftBytes, Files.readAllBytes(source));
            assertNoPublicationTemporaryFiles();
        } finally {
            continueCopy.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedFallbackCopyPreservesConcurrentTargetAndCleansPrivateStage() throws IOException {
        AtomicInteger linkAttempts = new AtomicInteger();
        AtomicReference<Path> concurrentTarget = new AtomicReference<>();
        byte[] concurrentBytes = new byte[]{6, 7, 8};
        ArtifactService service = new ArtifactService() {
            @Override
            void createPublicationLink(Path target, Path source) throws IOException {
                if (target.equals(concurrentTarget.get()) && linkAttempts.getAndIncrement() == 0) {
                    throw new IOException("force private staging");
                }
                super.createPublicationLink(target, source);
            }

            @Override
            void copyPublicationStage(Path source, FileChannel output) throws IOException {
                writeFully(output, ByteBuffer.wrap(new byte[]{1, 2}));
                Files.write(concurrentTarget.get(), concurrentBytes, StandardOpenOption.CREATE_NEW);
                throw new IOException("injected staged copy failure");
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path reservedTarget = draft.getTargetFile().toPath();
        concurrentTarget.set(reservedTarget.toAbsolutePath().normalize());
        Files.write(draft.getTemporaryFile().toPath(), new byte[]{0, 1, 2, 3});

        assertThrows(IllegalStateException.class, () -> service.publish(draft));

        assertArrayEquals(concurrentBytes, Files.readAllBytes(reservedTarget));
        assertTrue(Files.exists(draft.getTemporaryFile().toPath()));
        assertNoPublicationTemporaryFiles();
        service.deleteDraft(draft);
    }

    @Test
    void preexistingPrivateStageIsNotClaimedOrDeleted() throws IOException {
        AtomicInteger linkAttempts = new AtomicInteger();
        AtomicReference<Path> forcedTarget = new AtomicReference<>();
        ArtifactService service = new ArtifactService() {
            @Override
            void createPublicationLink(Path target, Path source) throws IOException {
                if (target.equals(forcedTarget.get()) && linkAttempts.getAndIncrement() == 0) {
                    throw new IOException("force private staging");
                }
                super.createPublicationLink(target, source);
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path source = draft.getTemporaryFile().toPath();
        Path target = draft.getTargetFile().toPath();
        forcedTarget.set(target.toAbsolutePath().normalize());
        Path occupiedStage = source.resolveSibling(source.getFileName() + ".publish-stage");
        byte[] occupiedBytes = new byte[]{8, 8, 8};
        Files.write(source, new byte[]{0, 1, 2});
        Files.write(occupiedStage, occupiedBytes, StandardOpenOption.CREATE_NEW);

        assertThrows(IllegalStateException.class, () -> service.publish(draft));

        assertArrayEquals(occupiedBytes, Files.readAllBytes(occupiedStage));
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(target));
        service.deleteDraft(draft);
    }

    @Test
    void unsupportedNoClobberPrimitiveFailsWithoutPublishingOrLosingDraft() throws IOException {
        ArtifactService service = new ArtifactService() {
            @Override
            void createPublicationLink(Path target, Path source) {
                throw new UnsupportedOperationException("hard links unavailable");
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path target = draft.getTargetFile().toPath();
        Path source = draft.getTemporaryFile().toPath();
        byte[] bytes = new byte[]{0, 1, 2, 3};
        Files.write(source, bytes);

        assertThrows(IllegalStateException.class, () -> service.publish(draft));

        assertFalse(Files.exists(target));
        assertArrayEquals(bytes, Files.readAllBytes(source));
        assertNoPublicationTemporaryFiles();
        service.deleteDraft(draft);
    }

    @Test
    void sourceReplacementBeforeAnchorCreationFailsClosed() throws Exception {
        CountDownLatch sourceCaptured = new CountDownLatch(1);
        CountDownLatch continueAnchorCreation = new CountDownLatch(1);
        ArtifactService service = new ArtifactService() {
            @Override
            void beforeSourceAnchorCreated(Path source) throws IOException {
                sourceCaptured.countDown();
                awaitBarrier(continueAnchorCreation, "source anchor creation");
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path source = draft.getTemporaryFile().toPath();
        Path target = draft.getTargetFile().toPath();
        byte[] replacementBytes = new byte[]{7, 7, 7};
        Files.write(source, new byte[]{0, 1, 2});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> publication = executor.submit(() -> service.publish(draft));
            assertTrue(sourceCaptured.await(5, TimeUnit.SECONDS));
            Files.delete(source);
            Files.write(source, replacementBytes, StandardOpenOption.CREATE_NEW);
            continueAnchorCreation.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> publication.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertArrayEquals(replacementBytes, Files.readAllBytes(source));
            assertFalse(Files.exists(target));
            assertNoPublicationTemporaryFiles();
        } finally {
            continueAnchorCreation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sourceReplacedWithSymlinkBeforeAnchorCreationFailsClosed() throws Exception {
        CountDownLatch sourceCaptured = new CountDownLatch(1);
        CountDownLatch continueAnchorCreation = new CountDownLatch(1);
        ArtifactService service = new ArtifactService() {
            @Override
            void beforeSourceAnchorCreated(Path source) throws IOException {
                sourceCaptured.countDown();
                awaitBarrier(continueAnchorCreation, "symlink source anchor creation");
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path source = draft.getTemporaryFile().toPath();
        Path target = draft.getTargetFile().toPath();
        Path symlinkTarget = Files.write(tempDirectory.resolve("symlink-target.csv"), new byte[]{9, 9, 9});
        Files.write(source, new byte[]{0, 1, 2});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> publication = executor.submit(() -> service.publish(draft));
            assertTrue(sourceCaptured.await(5, TimeUnit.SECONDS));
            Files.delete(source);
            Files.createSymbolicLink(source, symlinkTarget.getFileName());
            continueAnchorCreation.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> publication.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(Files.isSymbolicLink(source));
            assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(symlinkTarget));
            assertFalse(Files.exists(target));
            assertNoPublicationTemporaryFiles();
        } finally {
            continueAnchorCreation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedSourceCleanupIsTrackedAndRetried() throws IOException {
        AtomicReference<Path> sourcePath = new AtomicReference<>();
        AtomicInteger sourceValidationAttempts = new AtomicInteger();
        ArtifactService service = new ArtifactService() {
            @Override
            void afterCleanupQuarantineValidated(Path original, Path quarantine) throws IOException {
                if (original.equals(sourcePath.get()) && sourceValidationAttempts.getAndIncrement() == 0) {
                    throw new IOException("injected source cleanup failure");
                }
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path source = draft.getTemporaryFile().toPath().toAbsolutePath().normalize();
        sourcePath.set(source);
        Files.write(source, new byte[]{0, 1, 2});

        String artifactId = service.publish(draft);
        assertFalse(Files.exists(source));
        assertTrue(hasCleanupQuarantine());

        var retryTrigger = service.createDraft(2L, tempDirectory.toString(), "other.csv", "text/csv");
        assertFalse(Files.exists(source));
        assertEquals(2, sourceValidationAttempts.get());
        assertTrue(Files.exists(Path.of(artifactId)));
        assertNoPublicationTemporaryFiles();
        service.deleteDraft(retryTrigger);
    }

    @Test
    void replacementAfterCleanupValidationIsNeverUnlinked() throws Exception {
        AtomicReference<Path> sourcePath = new AtomicReference<>();
        AtomicBoolean blockCleanup = new AtomicBoolean(true);
        CountDownLatch cleanupValidated = new CountDownLatch(1);
        CountDownLatch continueCleanup = new CountDownLatch(1);
        byte[] replacementBytes = new byte[]{7, 7, 7};
        ArtifactService service = new ArtifactService() {
            @Override
            void afterCleanupQuarantineValidated(Path original, Path quarantine) throws IOException {
                if (original.equals(sourcePath.get()) && blockCleanup.compareAndSet(true, false)) {
                    cleanupValidated.countDown();
                    awaitBarrier(continueCleanup, "quarantined source cleanup");
                }
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path source = draft.getTemporaryFile().toPath().toAbsolutePath().normalize();
        sourcePath.set(source);
        byte[] generatedBytes = new byte[]{0, 1, 2};
        Files.write(source, generatedBytes);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> publication = executor.submit(() -> service.publish(draft));
            assertTrue(cleanupValidated.await(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(source));
            Files.write(source, replacementBytes, StandardOpenOption.CREATE_NEW);
            continueCleanup.countDown();
            Path artifact = Path.of(publication.get(5, TimeUnit.SECONDS));

            assertArrayEquals(replacementBytes, Files.readAllBytes(source));
            assertArrayEquals(generatedBytes, Files.readAllBytes(artifact));
            assertNoPublicationTemporaryFiles();
        } finally {
            continueCleanup.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void replacementOfValidatedQuarantineIsRestoredInsteadOfUnlinked() throws IOException {
        AtomicReference<Path> sourcePath = new AtomicReference<>();
        AtomicBoolean replaceQuarantine = new AtomicBoolean(true);
        byte[] quarantineReplacement = new byte[]{4, 4, 4, 4};
        ArtifactService service = new ArtifactService() {
            @Override
            void afterCleanupQuarantineValidated(Path original, Path quarantine) throws IOException {
                if (original.equals(sourcePath.get()) && replaceQuarantine.compareAndSet(true, false)) {
                    Files.delete(quarantine);
                    Files.write(quarantine, quarantineReplacement, StandardOpenOption.CREATE_NEW);
                }
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Path source = draft.getTemporaryFile().toPath().toAbsolutePath().normalize();
        sourcePath.set(source);
        byte[] generatedBytes = new byte[]{0, 1, 2};
        Files.write(source, generatedBytes);

        Path artifact = Path.of(service.publish(draft));

        assertArrayEquals(quarantineReplacement, Files.readAllBytes(source));
        assertArrayEquals(generatedBytes, Files.readAllBytes(artifact));
        assertNoPublicationTemporaryFiles();
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

    private Path findPublicationStage() throws IOException {
        try (var files = Files.list(tempDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".publish-stage"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private void assertNoPublicationTemporaryFiles() throws IOException {
        try (var files = Files.list(tempDirectory)) {
            assertTrue(files.noneMatch(path -> {
                String fileName = path.getFileName().toString();
                return fileName.endsWith(".publish-stage") || fileName.endsWith(".identity-anchor")
                        || fileName.contains(".cleanup-");
            }));
        }
    }

    private boolean hasCleanupQuarantine() throws IOException {
        try (var files = Files.list(tempDirectory)) {
            return files.anyMatch(path -> {
                String fileName = path.getFileName().toString();
                return fileName.contains(".cleanup-") && !fileName.endsWith(".cleanup-anchor");
            });
        }
    }

    private static void awaitBarrier(CountDownLatch barrier, String description) throws IOException {
        try {
            if (!barrier.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for " + description);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + description, e);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
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
