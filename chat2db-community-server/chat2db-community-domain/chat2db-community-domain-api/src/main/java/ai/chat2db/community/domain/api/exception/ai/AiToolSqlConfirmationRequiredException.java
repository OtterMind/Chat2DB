package ai.chat2db.community.domain.api.exception.ai;

public class AiToolSqlConfirmationRequiredException extends AiToolException {

    public AiToolSqlConfirmationRequiredException(String message) {
        super(message);
    }

    public AiToolSqlConfirmationRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
