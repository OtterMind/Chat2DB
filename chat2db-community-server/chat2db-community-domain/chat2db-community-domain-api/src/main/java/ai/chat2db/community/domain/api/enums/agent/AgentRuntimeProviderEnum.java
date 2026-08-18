package ai.chat2db.community.domain.api.enums.agent;

public enum AgentRuntimeProviderEnum {
    SPRING_AI,
    CODEX,
    HERMES,
    DSH;

    public boolean isExternal() {
        return switch (this) {
            case SPRING_AI -> false;
            case CODEX, HERMES, DSH -> true;
        };
    }

    public String displayName() {
        return switch (this) {
            case SPRING_AI -> "Spring AI";
            case CODEX -> "Codex";
            case HERMES -> "Hermes";
            case DSH -> "DeepSeek Harness";
        };
    }

    public String defaultExecutable() {
        return switch (this) {
            case SPRING_AI -> null;
            case CODEX -> "codex";
            case HERMES -> "hermes";
            case DSH -> "dsh";
        };
    }

    public boolean requiresApprovalBridge() {
        return switch (this) {
            case SPRING_AI, CODEX -> false;
            case HERMES, DSH -> true;
        };
    }
}
