package ai.chat2db.community.domain.api.exception.ai;

public class AiToolSqlExecutionException extends AiToolException {

    public AiToolSqlExecutionException(String message) {
        super(message);
    }

    public AiToolSqlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
