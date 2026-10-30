package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.service.task.ArtifactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void concurrentDraftsReserveDifferentTargetsAndPublishIndependently() throws IOException {
        ArtifactService service = new ArtifactServiceImpl();
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
        ArtifactService service = new ArtifactServiceImpl();
        var failed = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");

        assertThrows(IllegalStateException.class, () -> service.publish(failed));

        var replacement = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertEquals(failed.getTargetFile(), replacement.getTargetFile());
        service.deleteDraft(replacement);
    }

    @Test
    void publicationPreservesFileCreatedAfterReservation() throws IOException {
        ArtifactService service = new ArtifactServiceImpl();
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Files.writeString(draft.getTemporaryFile().toPath(), "exported");
        Files.writeString(draft.getTargetFile().toPath(), "user file");
        Files.createDirectory(tempDirectory.resolve("export_1.csv"));

        Path published = Path.of(service.publish(draft, artifactId -> {
            assertTrue(Path.of(artifactId).toFile().isFile());
            assertEquals(0, Path.of(artifactId).toFile().length());
        }));

        assertEquals("export_2.csv", published.getFileName().toString());
        assertEquals(published, draft.getTargetFile().toPath());
        assertEquals("exported", Files.readString(published));
        assertEquals("user file", Files.readString(tempDirectory.resolve("export.csv")));
        assertTrue(Files.isDirectory(tempDirectory.resolve("export_1.csv")));
        assertFalse(Files.exists(draft.getTemporaryFile().toPath()));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void danglingSymlinkIsAnOccupiedName() throws IOException {
        ArtifactService service = new ArtifactServiceImpl();
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Files.writeString(draft.getTemporaryFile().toPath(), "exported");
        Path missing = tempDirectory.resolve("missing.csv");
        Files.createSymbolicLink(draft.getTargetFile().toPath(), missing);

        Path published = Path.of(service.publish(draft));

        assertEquals("export_1.csv", published.getFileName().toString());
        assertEquals("exported", Files.readString(published));
        assertTrue(Files.isSymbolicLink(tempDirectory.resolve("export.csv")));
        assertFalse(Files.exists(missing));
    }

    @Test
    void independentServicesCanPublishTheSameNameConcurrently() throws Exception {
        ArtifactService first = new ArtifactServiceImpl();
        ArtifactService second = new ArtifactServiceImpl();
        var firstDraft = first.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        var secondDraft = second.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertEquals(firstDraft.getTargetFile(), secondDraft.getTargetFile());
        Files.writeString(firstDraft.getTemporaryFile().toPath(), "first");
        Files.writeString(secondDraft.getTemporaryFile().toPath(), "second");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var firstResult = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return first.publish(firstDraft);
            });
            var secondResult = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return second.publish(secondDraft);
            });
            start.countDown();
            Path firstPath = Path.of(firstResult.get(5, TimeUnit.SECONDS));
            Path secondPath = Path.of(secondResult.get(5, TimeUnit.SECONDS));
            assertNotEquals(firstPath, secondPath);
            assertEquals("first", Files.readString(firstPath));
            assertEquals("second", Files.readString(secondPath));
            assertFalse(Files.exists(firstDraft.getTemporaryFile().toPath()));
            assertFalse(Files.exists(secondDraft.getTemporaryFile().toPath()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void publicationMovesTheCompletedDraftInsteadOfCopyingIt() throws IOException {
        ArtifactService service = new ArtifactServiceImpl() {
            @Override
            void copyArtifact(Path source, OutputStream output) {
                throw new AssertionError("publish must not copy the draft when the filesystem can move it");
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Files.writeString(draft.getTemporaryFile().toPath(), "complete draft");

        Path published = Path.of(service.publish(draft));

        assertEquals("export.csv", published.getFileName().toString());
        assertEquals("complete draft", Files.readString(published));
        assertFalse(Files.exists(draft.getTemporaryFile().toPath()));
    }

    @Test
    void publicationFallsBackToCopyWhenTheFilesystemCannotMove() throws IOException {
        List<String> copied = new ArrayList<>();
        ArtifactService service = new ArtifactServiceImpl() {
            @Override
            void moveArtifact(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                        "target is on another device");
            }

            @Override
            void copyArtifact(Path source, OutputStream output) throws IOException {
                copied.add(source.getFileName().toString());
                super.copyArtifact(source, output);
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Files.writeString(draft.getTemporaryFile().toPath(), "complete draft");

        Path published = Path.of(service.publish(draft));

        assertEquals(1, copied.size());
        assertEquals("export.csv", published.getFileName().toString());
        assertEquals("complete draft", Files.readString(published));
        assertFalse(Files.exists(draft.getTemporaryFile().toPath()));
    }

    @Test
    void copyFailureRemovesOnlyTheCreatedTargetAndDoesNotRetry() throws IOException {
        IOException failure = new FileAlreadyExistsException("copy failure, not a name collision");
        ArtifactService service = new ArtifactServiceImpl() {
            @Override
            void moveArtifact(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                        "fall back to the copy path");
            }

            @Override
            void copyArtifact(Path source, OutputStream output) throws IOException {
                output.write('x');
                throw failure;
            }
        };
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Files.writeString(draft.getTemporaryFile().toPath(), "complete draft");
        Files.writeString(draft.getTargetFile().toPath(), "user file");

        var thrown = assertThrows(IllegalStateException.class, () -> service.publish(draft));

        assertSame(failure, thrown.getCause());
        assertEquals("user file", Files.readString(tempDirectory.resolve("export.csv")));
        assertEquals("complete draft", Files.readString(draft.getTemporaryFile().toPath()));
        assertFalse(Files.exists(draft.getTargetFile().toPath()));
        assertEquals("export_1.csv", draft.getTargetFile().getName());
        var replacement = service.createDraft(2L, tempDirectory.toString(), "export.csv", "text/csv");
        assertEquals(draft.getTargetFile(), replacement.getTargetFile());
        service.deleteDraft(replacement);
    }

    @Test
    void recoveryRecordFailureStopsPublicationAndKeepsDraft() throws IOException {
        ArtifactService service = new ArtifactServiceImpl();
        var draft = service.createDraft(1L, tempDirectory.toString(), "export.csv", "text/csv");
        Files.writeString(draft.getTemporaryFile().toPath(), "complete draft");
        RuntimeException failure = new IllegalStateException("could not persist recovery record");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.publish(draft, artifactId -> { throw failure; })));

        assertFalse(Files.exists(draft.getTargetFile().toPath()));
        assertEquals("complete draft", Files.readString(draft.getTemporaryFile().toPath()));
    }

}
