package ai.chat2db.community.domain.api.exception.ai;

public class AiToolInvalidArgumentException extends AiToolException {

    public AiToolInvalidArgumentException(String message) {
        super(message);
    }

    public AiToolInvalidArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
