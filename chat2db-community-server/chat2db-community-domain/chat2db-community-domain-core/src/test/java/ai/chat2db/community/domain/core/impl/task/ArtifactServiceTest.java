package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.service.task.ArtifactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

}
