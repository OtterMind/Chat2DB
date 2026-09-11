package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;

import java.io.IOException;
import java.nio.file.Path;

/** Manages task output files and their temporary staging paths. */
public interface ArtifactService {
    ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType);

    String publish(ArtifactDraft draft);

    void deleteDraft(ArtifactDraft draft);

    void deletePublished(String artifactId);

    /** Stages a file only when the destination is absent; an already staged file is left in place. */
    void stageForDeletion(Path original, Path staged) throws IOException;

    boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath);
}
