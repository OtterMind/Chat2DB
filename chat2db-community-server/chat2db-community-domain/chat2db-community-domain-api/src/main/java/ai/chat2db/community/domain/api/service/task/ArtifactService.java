package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.service.task.ArtifactService;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * Finishes every publish this task left half-done, reporting whether any artifact still needed work.
     */
    boolean cleanupInterruptedArtifacts(Long taskId, List<String> temporaryPaths,
            List<String> publishedPaths);

    /**
     * A deletion that has been staged on disk but not yet committed. The intent survives a crash so a
     * restart can finish it instead of leaving the file behind.
     *
     * @param originalPath the published location the file will be restored to if the deletion aborts
     * @param stagedPath   the location the file currently occupies
     */
    record PublishedArtifactDeletion(String originalPath, Path stagedPath) {

        public static PublishedArtifactDeletion empty() {
            return new PublishedArtifactDeletion(null, null);
        }

        public boolean isEmpty() {
            return stagedPath == null;
        }
    }

    /**
     * Draft creation that records the owning task role, so a resumed task can tell its own draft apart
     * from another task's leftovers in the same directory.
     */
    ArtifactDraft createDraft(Long taskId, String role, String outputDirectory, String fileName, String mediaType);

    /** Continues writing into a draft this task created earlier, rather than starting a new one. */
    ArtifactDraft resumeDraft(Long taskId, String role, String outputDirectory, String fileName, String mediaType,
            ArtifactDraft existing);

    /** Whether the file looks like a draft this task never finished publishing. */
    static boolean isInterruptedDraft(Long taskId, File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName();
        return name.contains(String.valueOf(taskId)) && name.endsWith(".part");
    }

    /**
     * Moves a published artifact aside without deleting it, so the deletion intent can be recorded first
     * and only then committed.
     */
    PublishedArtifactDeletion stagePublishedDeletion(String artifactId);

    /** Deletes the staged file for good, completing a previously staged deletion. */
    void commitPublishedDeletion(PublishedArtifactDeletion deletion);

    /** Puts a staged file back where it was published, undoing a staged deletion that must not proceed. */
    void restorePublishedDeletion(PublishedArtifactDeletion deletion);
}
