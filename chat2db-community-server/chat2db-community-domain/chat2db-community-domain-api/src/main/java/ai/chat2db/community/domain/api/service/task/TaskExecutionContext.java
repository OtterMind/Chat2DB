package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;

import java.util.Map;

public interface TaskExecutionContext extends ISqlExecutionStatementListener {

    /** The task these callbacks belong to; null outside a task run. */
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

    /** Cancels registered work after execution fails, without changing the task's failure status. */
    default void cancelResources() {
    }

    ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType);

    void write(String content);
}
