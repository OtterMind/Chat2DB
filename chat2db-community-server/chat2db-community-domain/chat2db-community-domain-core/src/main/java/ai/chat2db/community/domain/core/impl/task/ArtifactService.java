package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ArtifactService {

    private static final String DRAFT_FILE_SUFFIX = ".part";

    private static final String DELETION_FILE_MARKER = ".task-delete-";

    private final Set<Path> reservedTargets = ConcurrentHashMap.newKeySet();

    ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType) {
        File directory = resolveDirectory(outputDirectory);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create artifact directory");
        }
        String safeFileName = safeFileName(fileName);
        File target = reserveAvailableTarget(directory, safeFileName);
        File temporary = new File(directory,
                ".task-" + taskId + "-" + UUID.randomUUID() + "-" + safeFileName + DRAFT_FILE_SUFFIX);
        return ArtifactDraft.builder()
                .temporaryFile(temporary)
                .targetFile(target)
                .mediaType(mediaType)
                .build();
    }

    String publish(ArtifactDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Artifact draft is incomplete");
        }
        try {
            if (draft.getTemporaryFile() == null || draft.getTargetFile() == null) {
                throw new IllegalArgumentException("Artifact draft is incomplete");
            }
            Path source = draft.getTemporaryFile().toPath();
            Path target = draft.getTargetFile().toPath();
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                throw new IllegalStateException("Artifact draft is not readable");
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not publish artifact", e);
        } finally {
            releaseTarget(draft);
        }
    }

    void deleteDraft(ArtifactDraft draft) {
        if (draft == null) {
            return;
        }
        try {
            if (draft.getTemporaryFile() != null) {
                Files.deleteIfExists(draft.getTemporaryFile().toPath());
            }
        } catch (IOException ignored) {
            // A failed cleanup must not overwrite the task's terminal result.
        } finally {
            releaseTarget(draft);
        }
    }

    void deletePublished(String artifactId) {
        if (StringUtils.isBlank(artifactId)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(artifactId));
        } catch (IOException ignored) {
            // Best effort rollback when a terminal compare-and-set loses.
        }
    }

    PublishedArtifactDeletion stagePublishedDeletion(String artifactId) {
        if (StringUtils.isBlank(artifactId)) {
            return PublishedArtifactDeletion.empty();
        }
        Path original = Path.of(artifactId).toAbsolutePath().normalize();
        if (!Files.exists(original)) {
            return PublishedArtifactDeletion.empty();
        }
        if (!Files.isRegularFile(original)) {
            throw artifactDeletionFailure(artifactId, null);
        }
        Path staged = original.resolveSibling("." + original.getFileName()
                + DELETION_FILE_MARKER + UUID.randomUUID());
        try {
            move(original, staged);
            return new PublishedArtifactDeletion(original, staged);
        } catch (Exception e) {
            throw artifactDeletionFailure(artifactId, e);
        }
    }

    void commitPublishedDeletion(PublishedArtifactDeletion deletion) {
        if (deletion == null || deletion.stagedPath() == null) {
            return;
        }
        try {
            Files.deleteIfExists(deletion.stagedPath());
        } catch (Exception e) {
            throw artifactDeletionFailure(deletion.originalPath().toString(), e);
        }
    }

    void restorePublishedDeletion(PublishedArtifactDeletion deletion) {
        if (deletion == null || deletion.stagedPath() == null || !Files.exists(deletion.stagedPath())) {
            return;
        }
        try {
            move(deletion.stagedPath(), deletion.originalPath());
        } catch (Exception e) {
            throw artifactDeletionFailure(deletion.originalPath().toString(), e);
        }
    }

    void cleanupInterruptedArtifact(Long taskId, String temporaryPath, String targetPath) {
        if (StringUtils.isNotBlank(temporaryPath)) {
            Path temporary = Path.of(temporaryPath).toAbsolutePath().normalize();
            String fileName = temporary.getFileName() == null ? "" : temporary.getFileName().toString();
            if (fileName.startsWith(".task-" + taskId + "-") && fileName.endsWith(DRAFT_FILE_SUFFIX)) {
                deleteQuietly(temporary);
            }
        }
        if (StringUtils.isNotBlank(targetPath)) {
            deleteQuietly(Path.of(targetPath).toAbsolutePath().normalize());
        }
    }

    private File resolveDirectory(String outputDirectory) {
        if (StringUtils.isNotBlank(outputDirectory)) {
            return new File(outputDirectory);
        }
        File downloads = new File(System.getProperty("user.home"), "Downloads");
        if (downloads.exists() || downloads.mkdirs()) {
            return downloads;
        }
        return new File(ConfigUtils.getEnvBasePath(), "artifacts");
    }

    private String safeFileName(String fileName) {
        String safeName = new File(StringUtils.defaultIfBlank(fileName, "chat2db-export")).getName();
        if (StringUtils.isBlank(safeName) || ".".equals(safeName) || "..".equals(safeName)) {
            return "chat2db-export";
        }
        return safeName;
    }

    private File reserveAvailableTarget(File directory, String fileName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        String suffix = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 0; index < 1000; index++) {
            String candidateName = index == 0 ? fileName : baseName + "_" + index + suffix;
            File candidate = new File(directory, candidateName);
            Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
            if (!Files.exists(candidatePath) && reservedTargets.add(candidatePath)) {
                return candidate;
            }
        }
        while (true) {
            File candidate = new File(directory, baseName + "_" + UUID.randomUUID() + suffix);
            Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
            if (!Files.exists(candidatePath) && reservedTargets.add(candidatePath)) {
                return candidate;
            }
        }
    }

    private void releaseTarget(ArtifactDraft draft) {
        if (draft.getTargetFile() != null) {
            reservedTargets.remove(draft.getTargetFile().toPath().toAbsolutePath().normalize());
        }
    }

    private void deleteQuietly(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The task is still converged to a terminal state even if filesystem cleanup fails.
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private BusinessException artifactDeletionFailure(String artifactId, Exception cause) {
        return new BusinessException(TaskConstants.DELETE_ARTIFACT_FAILED_MESSAGE_CODE,
                new Object[]{artifactId}, cause);
    }

    record PublishedArtifactDeletion(Path originalPath, Path stagedPath) {

        private static PublishedArtifactDeletion empty() {
            return new PublishedArtifactDeletion(null, null);
        }
    }
}
