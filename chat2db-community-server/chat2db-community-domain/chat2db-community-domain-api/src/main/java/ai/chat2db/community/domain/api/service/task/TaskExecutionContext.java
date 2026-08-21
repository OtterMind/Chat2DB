package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;

import java.util.Map;

public interface TaskExecutionContext extends ISqlExecutionStatementListener {

    void reportProgress(int progress, String stage, String message);

    void logInfo(String code, String message);

    void logInfo(String code, String message, Map<String, Object> details);

    void logWarn(String code, String message, Map<String, Object> details);

    void logError(String code, String message, Map<String, Object> details);

    void checkCancelled();

    void registerCancelable(TaskCancelable resource);

    ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType);

    void write(String content);
}
