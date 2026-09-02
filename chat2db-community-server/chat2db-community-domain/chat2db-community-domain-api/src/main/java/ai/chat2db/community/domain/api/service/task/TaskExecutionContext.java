package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;

import java.util.List;
import java.util.Map;

public interface TaskExecutionContext extends ISqlExecutionStatementListener {

    /**
     * The task these callbacks belong to; {@code null} for contexts outside a task run.
     */
    default Long taskId() {
        return null;
    }

    void reportProgress(int progress, String stage, String message);

    void logInfo(String code, String message);

    void logInfo(String code, String message, Map<String, Object> details);

    void logWarn(String code, String message, Map<String, Object> details);

    void logError(String code, String message, Map<String, Object> details);

    void checkCancelled();

    void registerCancelable(TaskCancelable resource);

    default ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
        return createArtifact(TaskArtifactRole.OUTPUT, outputDirectory, fileName, mediaType);
    }

    /**
     * Creates one draft per artifact role; the primary download uses {@code OUTPUT}.
     */
    ArtifactDraft createArtifact(String role, String outputDirectory, String fileName, String mediaType);

    void write(String content);

    /**
     * Checkpoints persisted by earlier attempts of this task, so an exporter can resume where the
     * previous run stopped.
     */
    default List<ResumeState> resumeStates() {
        return List.of();
    }

    /**
     * Persists one shard checkpoint (keyed by {@code ResumeState.shardNo}) for a later resume.
     */
    default void checkpoint(ResumeState state) {
    }
}
