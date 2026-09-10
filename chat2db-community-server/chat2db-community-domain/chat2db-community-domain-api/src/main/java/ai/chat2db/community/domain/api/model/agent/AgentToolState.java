package ai.chat2db.community.domain.api.model.agent;

public record AgentToolState(String name, String description, Category category, Status status) {
    public enum Category { DATABASE, BUILTIN, INTERACTION }
    public enum Status { ENABLED, DISABLED, UNAVAILABLE }
}
