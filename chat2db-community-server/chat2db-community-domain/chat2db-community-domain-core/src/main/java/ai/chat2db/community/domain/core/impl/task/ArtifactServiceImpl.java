package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.service.task.ArtifactService;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class ArtifactServiceImpl implements ArtifactService {

    private static final String DRAFT_FILE_SUFFIX = ".part";

    private final Set<Path> reservedTargets = ConcurrentHashMap.newKeySet();

    @Override
    public ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType) {
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

    @Override
    public String publish(ArtifactDraft draft) {
        return publish(draft, artifactId -> {});
    }

    @Override
    public String publish(ArtifactDraft draft, Consumer<String> onTargetCreated) {
        if (draft == null) {
            throw new IllegalArgumentException("Artifact draft is incomplete");
        }
        try {
            if (draft.getTemporaryFile() == null || draft.getTargetFile() == null) {
                throw new IllegalArgumentException("Artifact draft is incomplete");
            }
            Path source = draft.getTemporaryFile().toPath();
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                throw new IllegalStateException("Artifact draft is not readable");
            }
            // Reserve the target name by creating the empty file first; the completed draft then
            // replaces it, so the name cannot be taken by another writer in between.
            try (OutputStream ignored = createTarget(draft)) {
                // The reserved target stays empty until the draft is published.
            }
            Path target = draft.getTargetFile().toPath();
            try {
                onTargetCreated.accept(target.toAbsolutePath().toString());
                publishArtifact(source, target);
                Files.deleteIfExists(source);
                return target.toAbsolutePath().toString();
            } catch (IOException | RuntimeException | Error e) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not publish artifact", e);
        } finally {
            releaseTarget(draft);
        }
    }

    /**
     * Publishes the completed draft with an atomic rename, which avoids copying the whole artifact
     * again. Filesystems that cannot rename (for example across devices) fall back to the copy path.
     */
    void publishArtifact(Path source, Path target) throws IOException {
        try {
            moveArtifact(source, target);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            copyToTarget(source, target);
        }
    }

    void moveArtifact(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private void copyToTarget(Path source, Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            copyArtifact(source, output);
        }
    }

    private OutputStream createTarget(ArtifactDraft draft) throws IOException {
        File requestedTarget = draft.getTargetFile().getAbsoluteFile();
        while (true) {
            try {
                // CREATE_NEW checks and creates atomically, including existing symbolic links.
                return Files.newOutputStream(draft.getTargetFile().toPath(), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException e) {
                releaseTarget(draft);
                draft.setTargetFile(reserveAvailableTarget(requestedTarget.getParentFile(), requestedTarget.getName()));
            }
        }
    }

    void copyArtifact(Path source, OutputStream output) throws IOException {
        Files.copy(source, output);
    }

    @Override
    public void deleteDraft(ArtifactDraft draft) {
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

    @Override
    public void deletePublished(String artifactId) {
        if (StringUtils.isBlank(artifactId)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(artifactId));
        } catch (IOException ignored) {
            // Best effort rollback when a terminal compare-and-set loses.
        }
    }

    @Override
    public void stageForDeletion(Path original, Path staged) throws IOException {
        if (Files.notExists(staged) && Files.exists(original)) {
            if (!Files.isRegularFile(original)) {
                throw new IOException("Task artifact is not a regular file: " + original);
            }
            Files.move(original, staged);
        }
    }

    @Override
    public boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath) {
        boolean cleaned = true;
        if (StringUtils.isNotBlank(temporaryPath)) {
            Path temporary = Path.of(temporaryPath).toAbsolutePath().normalize();
            String fileName = temporary.getFileName() == null ? "" : temporary.getFileName().toString();
            if (fileName.startsWith(".task-" + taskId + "-") && fileName.endsWith(DRAFT_FILE_SUFFIX)) {
                cleaned = deleteQuietly(temporary);
            }
        }
        if (StringUtils.isNotBlank(publishedPath)) {
            cleaned = deleteQuietly(Path.of(publishedPath).toAbsolutePath().normalize()) && cleaned;
        }
        return cleaned;
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
            if (!Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS) && reservedTargets.add(candidatePath)) {
                return candidate;
            }
        }
        while (true) {
            File candidate = new File(directory, baseName + "_" + UUID.randomUUID() + suffix);
            Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
            if (!Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS) && reservedTargets.add(candidatePath)) {
                return candidate;
            }
        }
    }

    private void releaseTarget(ArtifactDraft draft) {
        if (draft.getTargetFile() != null) {
            reservedTargets.remove(draft.getTargetFile().toPath().toAbsolutePath().normalize());
        }
    }

    private boolean deleteQuietly(Path path) {
        try {
            if (Files.notExists(path)) {
                return true;
            }
            if (Files.isRegularFile(path)) {
                Files.deleteIfExists(path);
                return Files.notExists(path);
            }
            return false;
        } catch (IOException ignored) {
            // The task is still converged to a terminal state even if filesystem cleanup fails.
            return false;
        }
    }

}
