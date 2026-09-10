package ai.chat2db.community.domain.api.model.agent.interaction;

import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import java.util.List;

public record AgentQuestion(String id, String sessionId, String runId, String toolCallId, Request request) {
    public record Request(String question, List<Option> options) { }
    public record Option(String id, String label, String description) { }
    public record Response(String optionId, String text) { }
    public record Answer(String questionId, String optionId, String optionLabel, String text) { }
    public record Result(boolean ok, Answer data) implements IAgentToolResult<Answer> { }
}
