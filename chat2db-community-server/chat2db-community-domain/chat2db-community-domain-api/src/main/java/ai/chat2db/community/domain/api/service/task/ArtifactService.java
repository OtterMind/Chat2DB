package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.service.task.ArtifactService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Manages task output files and their temporary staging paths. */
public interface ArtifactService {
    ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType);

    String publish(ArtifactDraft draft);

    /**
     * Records the owned destination for recovery. Implementations that copy file contents
     * must notify the listener after creating the destination and before writing any bytes.
     * The default preserves compatibility with implementations that publish a complete file.
     */
    default String publish(ArtifactDraft draft, Consumer<String> onTargetCreated) {
        String artifactId = publish(draft);
        try {
            onTargetCreated.accept(artifactId);
            return artifactId;
        } catch (RuntimeException | Error e) {
            deletePublished(artifactId);
            throw e;
        }
    }

    void deleteDraft(ArtifactDraft draft);

    void deletePublished(String artifactId);

    /** Stages a file only when the destination is absent; an already staged file is left in place. */
    void stageForDeletion(Path original, Path staged) throws IOException;

    boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath);
}
