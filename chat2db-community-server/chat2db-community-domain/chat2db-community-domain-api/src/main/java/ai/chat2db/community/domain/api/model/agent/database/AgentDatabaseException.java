package ai.chat2db.community.domain.api.model.agent.database;

public class AgentDatabaseException extends RuntimeException {
    private final String code;
    private final String field;
    private final AgentDatabaseResult.NextAction nextAction;

    public AgentDatabaseException(String code, String field, String message, AgentDatabaseResult.NextAction nextAction) {
        this(code, field, message, nextAction, null);
    }
    public AgentDatabaseException(String code, String field, String message, AgentDatabaseResult.NextAction nextAction, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.field = field;
        this.nextAction = nextAction;
    }
    public String code() { return code; }
    public String field() { return field; }
    public AgentDatabaseResult.NextAction nextAction() { return nextAction; }
}
