package ai.chat2db.community.domain.api.exception.ai;

public class AiToolMetadataQueryException extends AiToolException {

    public AiToolMetadataQueryException(String message) {
        super(message);
    }

    public AiToolMetadataQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
