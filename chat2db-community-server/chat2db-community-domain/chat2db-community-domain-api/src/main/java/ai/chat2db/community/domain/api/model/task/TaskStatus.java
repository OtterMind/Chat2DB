package ai.chat2db.community.domain.api.model.task;

public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

    public static boolean isTerminal(String value) {
        return SUCCESS.name().equals(value) || FAILED.name().equals(value) || CANCELLED.name().equals(value);
    }
}
