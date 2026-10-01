package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages task output files and their temporary staging paths.
 *
 * <p>A task may own several artifacts (a primary output plus diagnostics or rejects), so every
 * operation is keyed by {@link ArtifactDraft#getRole()} or by an explicit path rather than by a
 * single implicit output.
 */
public interface ArtifactService {

    /** Draft for the task's primary output. */
    ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType);

    /** Draft for one named role; the primary download uses {@code OUTPUT}. */
    ArtifactDraft createDraft(Long taskId, String role, String outputDirectory, String fileName, String mediaType);

    /**
     * Builds a draft around the interrupted run's temporary file, so a checkpointed export
     * continues appending where it stopped instead of restarting the artifact.
     */
    ArtifactDraft resumeDraft(Long taskId, String role, String outputDirectory, String fileName,
            String mediaType, File existingTemporaryFile);

    /**
     * Whether {@code file} is a draft this application wrote for this task, and therefore the only
     * kind of file a resume may safely reopen.
     */
    boolean isInterruptedDraft(Long taskId, File file);

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

    /**
     * Hides an owned artifact behind a sibling marker before the task row is deleted, so a failed
     * delete can restore it and a committed delete leaves nothing visible.
     */
    PublishedArtifactDeletion stagePublishedDeletion(String artifactId);

    /** Deletes a staged artifact for good; called only after the task row is durably gone. */
    void commitPublishedDeletion(PublishedArtifactDeletion deletion);

    /** Puts a staged artifact back, used when the task delete did not commit. */
    void restorePublishedDeletion(PublishedArtifactDeletion deletion);

    /** Removes the drafts and published files of one interrupted task. */
    boolean cleanupInterruptedArtifacts(Long taskId, List<String> temporaryPaths, List<String> publishedPaths);

    /** Single-path form of {@link #cleanupInterruptedArtifacts}. */
    default boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath) {
        List<String> temporaryPaths = temporaryPath == null ? List.of() : List.of(temporaryPath);
        List<String> publishedPaths = publishedPath == null ? List.of() : List.of(publishedPath);
        return cleanupInterruptedArtifacts(taskId, temporaryPaths, publishedPaths);
    }

    /**
     * A published artifact moved aside but not yet deleted. The staged file is invisible to
     * download until the deletion commits.
     */
    record PublishedArtifactDeletion(Path originalPath, Path stagedPath) {

        /** A no-op deletion, used when the artifact is already absent. */
        public static PublishedArtifactDeletion empty() {
            return new PublishedArtifactDeletion(null, null);
        }
    }
}
