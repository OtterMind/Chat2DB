package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;

import java.util.Map;

public interface TaskExecutionContext extends ISqlExecutionStatementListener {

    /** The task these callbacks belong to; null outside a task run. */
    default Long taskId() {
        return null;
    }

    void reportProgress(int progress, String stage, String message);

    /**
     * Reports progress with structured row counters and rate. The default keeps existing context
     * implementations source compatible; contexts that persist task snapshots should override it.
     */
    default void reportProgress(TaskProgress progress) {
        if (progress != null && progress.getProgress() != null) {
            reportProgress(progress.getProgress(), progress.getStage(), progress.getMessage());
        }
    }

    void logInfo(String code, String message);

    void logInfo(String code, String message, Map<String, Object> details);

    void logWarn(String code, String message, Map<String, Object> details);

    void logError(String code, String message, Map<String, Object> details);

    void checkCancelled();

    void registerCancelable(TaskCancelable resource);

    /** Cancels registered work after execution fails, without changing the task's failure status. */
    default void cancelResources() {
    }

    ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType);

    /**
     * Creates the secondary error artifact that lists the rows an import could not store. Like
     * {@link #createArtifact(String, String, String)} it stages a draft the task layer publishes
     * when the task reaches a terminal status. The default keeps existing context implementations
     * source compatible; a context that cannot stage an error file rejects the call instead of
     * silently dropping rejected rows.
     */
    default ArtifactDraft createErrorArtifact(String outputDirectory, String fileName, String mediaType) {
        throw new UnsupportedOperationException("Error artifacts are not supported by this task context");
    }

    /**
     * The error artifact draft of this task run, or {@code null} when no rejected row was recorded.
     */
    default ArtifactDraft errorArtifactDraft() {
        return null;
    }

    /**
     * Persists the resumable position of a long import so a new task can continue from it after a
     * failure or cancellation. The default keeps existing implementations source compatible; a
     * caller must tolerate a context that does not checkpoint.
     */
    default void saveCheckpoint(String checkpointJson) {
    }

    void write(String content);

    /**
     * Writes raw artifact bytes without adding a line separator, for exports that are not text.
     * The default keeps existing context implementations source compatible; a context that cannot
     * write bytes rejects the call instead of silently dropping data.
     */
    default void writeBytes(byte[] buffer, int offset, int length) {
        throw new UnsupportedOperationException("Byte writes are not supported by this task context");
    }
}
