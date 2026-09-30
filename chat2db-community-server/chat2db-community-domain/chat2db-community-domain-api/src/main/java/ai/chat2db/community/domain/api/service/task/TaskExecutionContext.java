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

    /**
     * Atomically enters a database commit boundary. Lifecycle cancellation cannot interrupt an
     * active boundary; executors with more cancel-safe work must pair this with
     * {@link #exitCommitPhase()} after the commit outcome is known.
     */
    default void enterCommitPhase() {
        checkCancelled();
    }

    /** Leaves a scoped commit boundary. A final task-wide commit may intentionally omit this. */
    default void exitCommitPhase() {
    }

    void registerCancelable(TaskCancelable resource);

    /**
     * Releases the resources this task still holds. A failing parallel worker calls it so its peers
     * stop writing before the task is torn down. The default keeps direct constructions working.
     */
    default void cancelResources() {
    }

    ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType);

    /**
     * Creates one draft per artifact role; the primary download uses {@code OUTPUT}.
     */
    default ArtifactDraft createArtifact(String role, String outputDirectory, String fileName, String mediaType) {
        if (!TaskArtifactRole.OUTPUT.equals(role)) {
            throw new UnsupportedOperationException("This task context supports only the primary output artifact");
        }
        return createArtifact(outputDirectory, fileName, mediaType);
    }

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
