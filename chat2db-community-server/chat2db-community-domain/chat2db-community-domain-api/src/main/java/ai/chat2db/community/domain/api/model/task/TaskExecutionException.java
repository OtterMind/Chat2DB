package ai.chat2db.community.domain.api.model.task;

import lombok.Getter;

@Getter
public class TaskExecutionException extends RuntimeException {

    private static final String DEFAULT_PUBLIC_MESSAGE = "Task execution failed";

    private final String code;

    private final String safeMessage;

    private final String safeReason;

    public TaskExecutionException(String code, String safeMessage) {
        this(code, safeMessage, null, null);
    }

    public TaskExecutionException(String code, String safeMessage, Throwable cause) {
        this(code, safeMessage, null, cause);
    }

    public TaskExecutionException(String code, String safeMessage, String safeReason, Throwable cause) {
        super(buildPublicMessage(safeMessage, safeReason), cause);
        this.code = code;
        this.safeMessage = sanitize(safeMessage);
        this.safeReason = sanitize(safeReason);
    }

    public String publicMessage() {
        return getMessage();
    }

    private static String buildPublicMessage(String safeMessage, String safeReason) {
        String message = sanitize(safeMessage);
        String reason = sanitize(safeReason);
        String publicMessage;
        if (message == null && reason == null) {
            publicMessage = DEFAULT_PUBLIC_MESSAGE;
        } else if (message == null) {
            publicMessage = reason;
        } else if (reason == null || message.equals(reason)) {
            publicMessage = message;
        } else {
            publicMessage = message + ": " + reason;
        }
        return truncate(publicMessage);
    }

    private static String sanitize(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : truncate(normalized);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private static String truncate(String value) {
        if (value.length() <= TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH) {
            return value;
        }
        return value.substring(0, TaskConstants.MAX_PUBLIC_ERROR_MESSAGE_LENGTH - 3) + "...";
    }
}
