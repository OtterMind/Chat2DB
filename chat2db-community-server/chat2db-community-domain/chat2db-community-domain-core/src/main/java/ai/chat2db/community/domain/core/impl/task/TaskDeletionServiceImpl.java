package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.service.task.ArtifactService;
import ai.chat2db.community.domain.api.service.task.TaskDeletionService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.JsonFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class TaskDeletionServiceImpl implements TaskDeletionService {
    static final int MAX_DELETION_ATTEMPTS = 3;

    private final TaskStorage storage;
    private final ArtifactService artifacts;
    private final File queueFile;

    @Autowired
    public TaskDeletionServiceImpl(TaskStorage storage, ArtifactService artifacts) {
        this(storage, artifacts, new File(ConfigUtils.getEnvBasePath(), "task-artifact-deletions.json"));
    }

    TaskDeletionServiceImpl(TaskStorage storage, ArtifactService artifacts, File queueFile) {
        this.storage = storage;
        this.artifacts = artifacts;
        this.queueFile = queueFile;
    }

    @Override
    public synchronized void delete(Task task) {
        if (storage.get(task.getId()).isEmpty()) {
            throw new DataNotFoundException();
        }
        try {
            List<PendingTaskDeletion> pending = readQueue();
            PendingTaskDeletion deletion = pending.stream()
                    .filter(entry -> Objects.equals(entry.getTaskId(), task.getId())).findFirst().orElse(null);
            if (deletion == null) {
                deletion = PendingTaskDeletion.create(task);
                pending.add(deletion);
            }
            if (!attemptDeletion(pending, deletion)) {
                throw new IOException(deletion.getLastError());
            }
        } catch (Exception e) {
            throw new BusinessException(TaskConstants.DELETE_ARTIFACT_FAILED_MESSAGE_CODE,
                    new Object[]{task.getArtifactId()}, e);
        }
    }

    @Override
    public synchronized void retryPendingDeletions() {
        List<PendingTaskDeletion> pending;
        try {
            pending = readQueue();
        } catch (Exception e) {
            log.error("Could not read pending task deletions from {}", queueFile, e);
            return;
        }
        for (PendingTaskDeletion deletion : List.copyOf(pending)) {
            try {
                if (isComplete(deletion)) {
                    pending.remove(deletion);
                    JsonFileUtils.writeAtomically(queueFile, pending);
                } else if (deletion.getAttempts() < MAX_DELETION_ATTEMPTS) {
                    attemptDeletion(pending, deletion);
                }
            } catch (Exception e) {
                log.error("Could not process pending deletion for task {}", deletion.getTaskId(), e);
            }
        }
    }

    private boolean attemptDeletion(List<PendingTaskDeletion> pending, PendingTaskDeletion deletion) throws IOException {
        deletion.beginAttempt();
        JsonFileUtils.writeAtomically(queueFile, pending);
        try {
            executeDeletion(deletion);
            pending.remove(deletion);
        } catch (Exception e) {
            deletion.recordFailure(e);
            log.warn("Could not delete task {} on attempt {}", deletion.getTaskId(), deletion.getAttempts(), e);
        }
        JsonFileUtils.writeAtomically(queueFile, pending);
        return deletion.getLastError() == null;
    }

    private void executeDeletion(PendingTaskDeletion deletion) throws IOException {
        if (storage.get(deletion.getTaskId()).isPresent()) {
            if (deletion.hasArtifact()) {
                artifacts.stageForDeletion(deletion.originalFile(), deletion.stagedFile());
            }
            if (!storage.deleteTerminalTask(deletion.getTaskId(), null)) {
                throw new IOException("Task is not terminal: " + deletion.getTaskId());
            }
        }
        // The original filename may now belong to a newer export; only delete the staged file.
        if (deletion.hasArtifact()) {
            Files.deleteIfExists(deletion.stagedFile());
        }
    }

    private boolean isComplete(PendingTaskDeletion deletion) {
        return storage.get(deletion.getTaskId()).isEmpty()
                && (!deletion.hasArtifact() || Files.notExists(deletion.stagedFile()));
    }

    @Override
    public synchronized File resolveArtifact(Task task) {
        for (PendingTaskDeletion deletion : readQueue()) {
            if (Objects.equals(task.getId(), deletion.getTaskId()) && deletion.hasArtifact()
                    && Files.exists(deletion.stagedFile())) {
                return deletion.stagedFile().toFile();
            }
        }
        return new File(task.getArtifactId());
    }

    private List<PendingTaskDeletion> readQueue() {
        List<PendingTaskDeletion> pending = JsonFileUtils.readArray(queueFile, PendingTaskDeletion.class);
        if (pending.stream().anyMatch(entry -> entry == null || entry.getTaskId() == null)) {
            throw new IllegalStateException("Invalid task deletion queue: " + queueFile);
        }
        return pending;
    }
}
