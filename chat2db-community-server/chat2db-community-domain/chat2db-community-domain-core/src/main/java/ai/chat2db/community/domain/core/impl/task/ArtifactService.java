package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ArtifactService {

    private static final String DRAFT_FILE_SUFFIX = ".part";

    private static final String PUBLICATION_STAGE_SUFFIX = ".publish-stage";

    private static final String CLEANUP_ANCHOR_SUFFIX = ".cleanup-anchor";

    private static final String IDENTITY_ANCHOR_SUFFIX = ".identity-anchor";

    private static final String CLEANUP_QUARANTINE_MARKER = ".cleanup-";

    private static final String DELETION_FILE_MARKER = ".task-delete-";

    private final Set<Path> reservedTargets = ConcurrentHashMap.newKeySet();

    private final Map<Path, CleanupCandidate> pendingCleanup = new ConcurrentHashMap<>();

    ArtifactDraft createDraft(Long taskId, String outputDirectory, String fileName, String mediaType) {
        retryPendingCleanup();
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
        retryPendingCleanup();
        CleanupCandidate publicationStageCleanup = null;
        CleanupCandidate sourceIdentityAnchorCleanup = null;
        CleanupCandidate sourceAnchorCleanup = null;
        boolean publicationStageLinked = false;
        boolean sourceIdentityAnchorAttached = false;
        boolean sourceAnchorsOwnedByCleanup = false;
        try {
            if (draft.getTemporaryFile() == null || draft.getTargetFile() == null) {
                throw new IllegalArgumentException("Artifact draft is incomplete");
            }
            Path source = draft.getTemporaryFile().toPath().toAbsolutePath().normalize();
            Path target = draft.getTargetFile().toPath().toAbsolutePath().normalize();
            if (!isRegularFileWithoutFollowingLinks(source) || !Files.isReadable(source)) {
                throw new IllegalStateException("Artifact draft is not readable");
            }
            Path directory = target.getParent();
            Path fileName = target.getFileName();
            if (directory == null || fileName == null) {
                throw new IllegalArgumentException("Artifact draft is incomplete");
            }
            CleanupCandidate sourceCleanup = captureCleanupCandidate(source, null);
            if (sourceCleanup == null) {
                throw new IllegalStateException("Artifact draft is not readable");
            }
            Path sourceIdentityAnchor = identityAnchor(source);
            try {
                createPublicationLink(sourceIdentityAnchor, source);
            } catch (IOException | UnsupportedOperationException e) {
                throw publicationFailure(e);
            }
            sourceIdentityAnchorCleanup = captureCleanupCandidate(sourceIdentityAnchor, null);
            if (sourceIdentityAnchorCleanup == null) {
                throw new IOException("Could not track the artifact draft identity anchor");
            }
            Path sourceAnchor = cleanupAnchor(source);
            beforeSourceAnchorCreated(source);
            try {
                createPublicationLink(sourceAnchor, source);
            } catch (IOException | UnsupportedOperationException e) {
                throw publicationFailure(e);
            }
            sourceAnchorCleanup = captureCleanupCandidate(sourceAnchor, null);
            if (sourceAnchorCleanup == null) {
                throw new IOException("Could not track the artifact draft source anchor");
            }
            sourceAnchorCleanup = sourceAnchorCleanup.withDependent(sourceIdentityAnchorCleanup);
            sourceIdentityAnchorAttached = true;
            if ((sourceCleanup.fileKey() != null
                    && !Objects.equals(sourceCleanup.fileKey(), sourceIdentityAnchorCleanup.fileKey()))
                    || !isRegularFileWithoutFollowingLinks(source)
                    || !isRegularFileWithoutFollowingLinks(sourceIdentityAnchor)
                    || !isRegularFileWithoutFollowingLinks(sourceAnchor)
                    || !Files.isSameFile(source, sourceAnchor)
                    || !Files.isSameFile(sourceIdentityAnchor, sourceAnchor)) {
                throw new IOException("Artifact draft changed while publication was starting");
            }
            sourceCleanup = sourceCleanup.withAnchor(sourceIdentityAnchor, sourceAnchorCleanup);
            Path publicationSource = sourceIdentityAnchor;
            Throwable directLinkFailure = null;
            // Only a successful hard link may expose the complete artifact at a public target path.
            while (true) {
                try {
                    createPublicationLink(target, publicationSource);
                } catch (FileAlreadyExistsException e) {
                    releaseTarget(draft);
                    File replacement = reserveAvailableTarget(directory.toFile(), fileName.toString());
                    draft.setTargetFile(replacement);
                    target = replacement.toPath().toAbsolutePath().normalize();
                    continue;
                } catch (IOException | UnsupportedOperationException e) {
                    if (publicationStageCleanup != null) {
                        IOException failure = publicationFailure(e);
                        if (directLinkFailure != null) {
                            failure.addSuppressed(directLinkFailure);
                        }
                        throw failure;
                    }
                    if (sourceCleanup == null) {
                        throw publicationFailure(e);
                    }
                    directLinkFailure = e;
                    publicationSource = publicationStage(source);
                    try {
                        publicationStageCleanup = preparePublicationStage(sourceIdentityAnchor, publicationSource);
                    } catch (IOException | UnsupportedOperationException copyFailure) {
                        IOException failure = publicationFailure(copyFailure);
                        failure.addSuppressed(e);
                        throw failure;
                    }
                    Path stageAnchor = cleanupAnchor(publicationSource);
                    try {
                        createPublicationLink(stageAnchor, publicationSource);
                    } catch (IOException | UnsupportedOperationException anchorFailure) {
                        IOException failure = publicationFailure(anchorFailure);
                        failure.addSuppressed(e);
                        throw failure;
                    }
                    CleanupCandidate stageAnchorCleanup = captureCleanupCandidate(stageAnchor, null);
                    if (stageAnchorCleanup == null) {
                        throw new IOException("Could not track the artifact publication cleanup anchor");
                    }
                    publicationStageCleanup = publicationStageCleanup.withAnchor(stageAnchor, stageAnchorCleanup);
                    continue;
                }

                if (publicationStageCleanup != null) {
                    publicationStageLinked = true;
                    cleanupOwned(publicationStageCleanup);
                }
                sourceAnchorsOwnedByCleanup = true;
                cleanupOwned(sourceCleanup);
                return target.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not publish artifact", e);
        } finally {
            if (!publicationStageLinked) {
                cleanupOwned(publicationStageCleanup);
            }
            if (!sourceAnchorsOwnedByCleanup) {
                cleanupOwned(sourceAnchorCleanup);
                if (!sourceIdentityAnchorAttached) {
                    cleanupOwned(sourceIdentityAnchorCleanup);
                }
            }
            releaseTarget(draft);
        }
    }

    void deleteDraft(ArtifactDraft draft) {
        if (draft == null) {
            return;
        }
        try {
            retryPendingCleanup();
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

    boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath) {
        boolean cleaned = true;
        if (StringUtils.isNotBlank(temporaryPath)) {
            Path temporary = Path.of(temporaryPath).toAbsolutePath().normalize();
            String fileName = temporary.getFileName() == null ? "" : temporary.getFileName().toString();
            if (fileName.startsWith(".task-" + taskId + "-") && fileName.endsWith(DRAFT_FILE_SUFFIX)) {
                cleaned = deleteQuietly(publicationStage(temporary));
                cleaned = deleteQuietly(cleanupAnchor(publicationStage(temporary))) && cleaned;
                cleaned = deleteQuietly(cleanupAnchor(temporary)) && cleaned;
                cleaned = deleteQuietly(identityAnchor(temporary)) && cleaned;
                cleaned = deleteQuietly(temporary) && cleaned;
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

    private Path publicationStage(Path source) {
        return source.resolveSibling(source.getFileName() + PUBLICATION_STAGE_SUFFIX);
    }

    private Path cleanupAnchor(Path source) {
        return source.resolveSibling(source.getFileName() + CLEANUP_ANCHOR_SUFFIX);
    }

    private Path identityAnchor(Path source) {
        return source.resolveSibling(source.getFileName() + IDENTITY_ANCHOR_SUFFIX);
    }

    void createPublicationLink(Path target, Path source) throws IOException {
        Files.createLink(target, source);
    }

    void beforeSourceAnchorCreated(Path source) throws IOException {
    }

    private CleanupCandidate preparePublicationStage(Path source, Path stage) throws IOException {
        boolean stageCreated = false;
        try (FileChannel output = FileChannel.open(stage, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            stageCreated = true;
            copyPublicationStage(source, output);
            output.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            if (stageCreated) {
                cleanupOwned(captureCleanupCandidate(stage, null));
            }
            throw e;
        }
        CleanupCandidate stageCleanup = captureCleanupCandidate(stage, null);
        if (stageCleanup == null) {
            throw new IOException("Filesystem cannot safely track the artifact publication stage");
        }
        return stageCleanup;
    }

    void copyPublicationStage(Path source, FileChannel output) throws IOException {
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
        }
    }

    void deletePublicationPath(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    void moveToCleanupQuarantine(Path source, Path quarantine) throws IOException {
        Files.move(source, quarantine, StandardCopyOption.ATOMIC_MOVE);
    }

    void afterCleanupQuarantineValidated(Path original, Path quarantine) throws IOException {
    }

    private CleanupCandidate captureCleanupCandidate(Path path, Path anchor) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            Object fileKey = readPublicationFileIdentity(path);
            Path normalized = path.toAbsolutePath().normalize();
            return new CleanupCandidate(normalized, fileKey, anchor, null, normalized);
        } catch (IOException ignored) {
            Path normalized = path.toAbsolutePath().normalize();
            return new CleanupCandidate(normalized, null, anchor, null, normalized);
        }
    }

    private void cleanupOwned(CleanupCandidate candidate) {
        if (candidate == null) {
            return;
        }
        CleanupAttempt attempt = tryCleanupOwned(candidate);
        pendingCleanup.remove(candidate.path(), candidate);
        if (attempt.retry() == null) {
            cleanupOwned(candidate.dependentCleanup());
        } else {
            pendingCleanup.put(attempt.retry().path(), attempt.retry());
        }
    }

    private CleanupAttempt tryCleanupOwned(CleanupCandidate candidate) {
        if (!Files.exists(candidate.path(), LinkOption.NOFOLLOW_LINKS)) {
            return CleanupAttempt.complete();
        }
        Path quarantine = cleanupQuarantine(candidate.path());
        try {
            moveToCleanupQuarantine(candidate.path(), quarantine);
        } catch (IOException | SecurityException ignored) {
            return CleanupAttempt.retry(candidate);
        }
        CleanupCandidate quarantined = candidate.withPath(quarantine);
        try {
            if (!matchesOwnedFile(quarantine, candidate)) {
                return restoreQuarantinedReplacement(quarantined);
            }
            afterCleanupQuarantineValidated(candidate.restorePath(), quarantine);
            if (!matchesOwnedFile(quarantine, candidate)) {
                return restoreQuarantinedReplacement(quarantined);
            }
            deletePublicationPath(quarantine);
            return CleanupAttempt.complete();
        } catch (IOException | SecurityException ignored) {
            return CleanupAttempt.retry(quarantined);
        }
    }

    private boolean matchesOwnedFile(Path path, CleanupCandidate candidate) throws IOException {
        if (candidate.fileKey() != null) {
            Object currentKey = readPublicationFileIdentity(path);
            if (!Objects.equals(candidate.fileKey(), currentKey)) {
                return false;
            }
        }
        return candidate.anchor() == null
                || Files.exists(candidate.anchor(), LinkOption.NOFOLLOW_LINKS)
                && Files.isSameFile(path, candidate.anchor());
    }

    private Object readPublicationFileIdentity(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private boolean isRegularFileWithoutFollowingLinks(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .isRegularFile();
    }

    private CleanupAttempt restoreQuarantinedReplacement(CleanupCandidate quarantined) {
        try {
            try {
                createPublicationLink(quarantined.restorePath(), quarantined.path());
            } catch (FileAlreadyExistsException e) {
                if (!Files.isSameFile(quarantined.restorePath(), quarantined.path())) {
                    return CleanupAttempt.retry(quarantined);
                }
            }
            try {
                deletePublicationPath(quarantined.path());
                return CleanupAttempt.complete();
            } catch (IOException cleanupFailure) {
                CleanupCandidate duplicate = captureCleanupCandidate(
                        quarantined.path(), quarantined.restorePath());
                if (duplicate == null) {
                    return CleanupAttempt.complete();
                }
                return CleanupAttempt.retry(duplicate.withDependent(quarantined.dependentCleanup()));
            }
        } catch (IOException | SecurityException ignored) {
            return CleanupAttempt.retry(quarantined);
        }
    }

    private Path cleanupQuarantine(Path path) {
        return path.resolveSibling("." + path.getFileName() + CLEANUP_QUARANTINE_MARKER + UUID.randomUUID());
    }

    private void retryPendingCleanup() {
        pendingCleanup.values().forEach(this::cleanupOwned);
    }

    private IOException publicationFailure(Throwable cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException("Filesystem does not support safe artifact publication", cause);
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

    private record CleanupCandidate(Path path, Object fileKey, Path anchor, CleanupCandidate dependentCleanup,
            Path restorePath) {

        private CleanupCandidate withAnchor(Path target) {
            return withAnchor(target, null);
        }

        private CleanupCandidate withAnchor(Path target, CleanupCandidate dependentCleanup) {
            return new CleanupCandidate(path, fileKey,
                    target == null ? null : target.toAbsolutePath().normalize(), dependentCleanup, restorePath);
        }

        private CleanupCandidate withPath(Path target) {
            return new CleanupCandidate(target.toAbsolutePath().normalize(), fileKey, anchor,
                    dependentCleanup, restorePath);
        }

        private CleanupCandidate withDependent(CleanupCandidate dependentCleanup) {
            return new CleanupCandidate(path, fileKey, anchor, dependentCleanup, restorePath);
        }
    }

    private record CleanupAttempt(CleanupCandidate retry) {

        private static CleanupAttempt complete() {
            return new CleanupAttempt(null);
        }

        private static CleanupAttempt retry(CleanupCandidate candidate) {
            return new CleanupAttempt(candidate);
        }
    }

}
