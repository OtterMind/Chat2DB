package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ArtifactService {

    private static final String DRAFT_FILE_SUFFIX = ".part";

    private static final String DELETION_FILE_MARKER = ".task-delete-";

    private final Set<Path> reservedTargets = ConcurrentHashMap.newKeySet();

    private final File deletionJournalFile;

    private final List<StagedArtifactDeletion> deletionJournal = new ArrayList<>();

    public ArtifactService() {
        this(new File(ConfigUtils.getEnvBasePath(), "task-artifact-deletions.json"));
    }

    ArtifactService(File deletionJournalFile) {
        this.deletionJournalFile = deletionJournalFile;
        loadDeletionJournal();
    }

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

    PublishedArtifactDeletion stagePublishedDeletion(Long taskId, String artifactId) {
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
        StagedArtifactDeletion journalEntry = new StagedArtifactDeletion(taskId,
                original.toString(), staged.toString());
        try {
            // Publish the recovery intent before moving the artifact. A crash
            // can then leave either an untouched artifact plus a harmless
            // journal entry, or a staged artifact that startup can recover.
            recordStagedDeletion(journalEntry);
            try {
                move(original, staged);
            } catch (Exception moveFailure) {
                try {
                    forgetStagedDeletion(staged);
                } catch (Exception journalFailure) {
                    moveFailure.addSuppressed(journalFailure);
                }
                throw moveFailure;
            }
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
            forgetStagedDeletion(deletion.stagedPath());
        } catch (Exception e) {
            throw artifactDeletionFailure(deletion.originalPath().toString(), e);
        }
    }

    void restorePublishedDeletion(PublishedArtifactDeletion deletion) {
        if (deletion == null || deletion.stagedPath() == null) {
            return;
        }
        try {
            if (Files.exists(deletion.stagedPath())) {
                move(deletion.stagedPath(), deletion.originalPath());
            }
            forgetStagedDeletion(deletion.stagedPath());
        } catch (Exception e) {
            throw artifactDeletionFailure(deletion.originalPath().toString(), e);
        }
    }

    /**
     * Startup replay of staged deletions left behind by a crash: when the task
     * record survived, the artifact must be moved back to its published name;
     * when the task record is gone the deletion had committed, so the staged
     * file is removed. The journal entry is cleared once applied.
     */
    void recoverStagedDeletion(StagedArtifactDeletion staged, boolean taskExists) {
        Path stagedPath = Path.of(staged.stagedPath());
        try {
            if (Files.exists(stagedPath)) {
                if (taskExists) {
                    move(stagedPath, Path.of(staged.originalPath()));
                } else {
                    Files.deleteIfExists(stagedPath);
                }
            }
            forgetStagedDeletion(stagedPath);
        } catch (Exception e) {
            throw artifactDeletionFailure(staged.originalPath(), e);
        }
    }

    private void recordStagedDeletion(StagedArtifactDeletion staged) throws IOException {
        synchronized (deletionJournal) {
            deletionJournal.add(staged);
            try {
                writeDeletionJournal();
            } catch (IOException e) {
                deletionJournal.remove(staged);
                throw e;
            }
        }
    }

    private void forgetStagedDeletion(Path stagedPath) throws IOException {
        synchronized (deletionJournal) {
            if (deletionJournal.removeIf(
                    staged -> Path.of(staged.stagedPath()).equals(stagedPath.toAbsolutePath().normalize()))) {
                writeDeletionJournal();
            }
        }
    }

    List<StagedArtifactDeletion> loadStagedDeletions() {
        synchronized (deletionJournal) {
            return List.copyOf(deletionJournal);
        }
    }

    private void loadDeletionJournal() {
        if (!deletionJournalFile.isFile()) {
            return;
        }
        try {
            for (String line : FileUtil.readUtf8Lines(deletionJournalFile)) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }
                try {
                    StagedArtifactDeletion staged = JSON.parseObject(line, StagedArtifactDeletion.class);
                    if (staged != null && StringUtils.isNotBlank(staged.stagedPath())) {
                        deletionJournal.add(staged);
                    }
                } catch (Exception malformed) {
                    // One corrupt line must not orphan the staged deletions
                    // recorded after it.
                    log.warn("Skipping malformed task artifact deletion journal entry: {}", line, malformed);
                }
            }
        } catch (Exception e) {
            log.error("Could not load task artifact deletion journal {}", deletionJournalFile, e);
        }
    }

    private void writeDeletionJournal() throws IOException {
        StringBuilder content = new StringBuilder();
        for (StagedArtifactDeletion staged : deletionJournal) {
            content.append(JSON.toJSONString(staged)).append(System.lineSeparator());
        }
        FileUtil.mkParentDirs(deletionJournalFile);
        File temporary = new File(deletionJournalFile.getParentFile(),
                deletionJournalFile.getName() + DRAFT_FILE_SUFFIX);
        FileUtil.writeUtf8String(content.toString(), temporary);
        move(temporary.toPath(), deletionJournalFile.toPath());
    }

    boolean cleanupInterruptedArtifact(Long taskId, String temporaryPath, String publishedPath) {
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

    /**
     * Journal entry for a staged artifact deletion; survives the crash window
     * between the rename and the commit/restore so startup can finish it.
     */
    record StagedArtifactDeletion(Long taskId, String originalPath, String stagedPath) {
    }
}
