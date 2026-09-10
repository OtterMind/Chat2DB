package ai.chat2db.community.tools.exception.agent;

public class AgentChartException extends RuntimeException {
    private final String code;
    private final String field;

    public AgentChartException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() { return code; }
    public String field() { return field; }
}
