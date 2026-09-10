package ai.chat2db.community.tools.exception.agent;

import ai.chat2db.community.tools.model.agent.tool.AgentToolNextAction;

public class AgentDatabaseException extends RuntimeException {
    private final String code;
    private final String field;
    private final AgentToolNextAction nextAction;

    public AgentDatabaseException(String code, String field, String message, AgentToolNextAction nextAction) {
        this(code, field, message, nextAction, null);
    }
    public AgentDatabaseException(String code, String field, String message, AgentToolNextAction nextAction, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.field = field;
        this.nextAction = nextAction;
    }
    public String code() { return code; }
    public String field() { return field; }
    public AgentToolNextAction nextAction() { return nextAction; }
}
