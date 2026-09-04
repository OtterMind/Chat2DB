package ai.chat2db.community.domain.api.model.task;

public enum TaskStage {
    PENDING,
    STARTING,
    QUERYING,
    READING,
    WRITING,
    EXPORTING,
    IMPORTING,
    FINALIZING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED
}
