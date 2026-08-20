package ai.chat2db.community.domain.api.enums.agent;

public enum AgentRuntimeProviderEnum {
    SPRING_AI,
    CLAUDE_CODE,
    CODEX,
    OPENCODE,
    PI,
    HERMES,
    DSH;

    public boolean isExternal() {
        return switch (this) {
            case SPRING_AI -> false;
            case CLAUDE_CODE, CODEX, OPENCODE, PI, HERMES, DSH -> true;
        };
    }

    public String displayName() {
        return switch (this) {
            case SPRING_AI -> "Spring AI";
            case CLAUDE_CODE -> "Claude Code";
            case CODEX -> "Codex";
            case OPENCODE -> "OpenCode";
            case PI -> "Pi";
            case HERMES -> "Hermes";
            case DSH -> "DeepSeek Harness";
        };
    }

    public String defaultExecutable() {
        return switch (this) {
            case SPRING_AI -> null;
            case CLAUDE_CODE -> "claude";
            case CODEX -> "codex";
            case OPENCODE -> "opencode";
            case PI -> "pi";
            case HERMES -> "hermes";
            case DSH -> "dsh";
        };
    }

    public boolean requiresApprovalBridge() {
        return switch (this) {
            case SPRING_AI, CLAUDE_CODE, CODEX, OPENCODE, PI -> false;
            case HERMES, DSH -> true;
        };
    }
}
