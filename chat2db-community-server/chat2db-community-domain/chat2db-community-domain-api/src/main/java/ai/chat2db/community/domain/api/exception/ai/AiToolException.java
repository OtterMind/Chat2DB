package ai.chat2db.community.domain.api.exception.ai;

public class AiToolException extends RuntimeException {

    protected AiToolException(String message) {
        super(message);
    }

    protected AiToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
