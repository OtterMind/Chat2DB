package ai.chat2db.community.domain.api.model.agent;

public record AgentFailure(String code, String message, boolean retryable) {

    public AgentFailure {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
