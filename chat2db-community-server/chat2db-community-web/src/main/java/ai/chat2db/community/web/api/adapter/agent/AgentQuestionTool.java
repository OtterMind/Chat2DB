package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.AgentQuestion;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeEventSink;
import ai.chat2db.community.domain.api.service.agent.IAiAgentQuestionService;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BooleanSupplier;

@Component
public class AgentQuestionTool {
    public static final String NAME = "askUserQuestion";
    private final IAiAgentQuestionService questions;
    private final JsonMapper json = JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public AgentQuestionTool(IAiAgentQuestionService questions) { this.questions = questions; }

    public AgentToolAccess.Tool definition() {
        var option = Map.of("type", "object", "properties", Map.of(
                "id", Map.of("type", "string", "pattern", "^[a-zA-Z0-9_-]{1,64}$", "description", "Stable unique option id."),
                "label", Map.of("type", "string", "minLength", 1, "maxLength", 120, "description", "A concise choice the user can select."),
                "description", Map.of("anyOf", List.of(Map.of("type", "string", "maxLength", 300), Map.of("type", "null")), "description", "Optional short reason or consequence; omit or use null when unnecessary.")),
                "required", List.of("id", "label"), "additionalProperties", false);
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of(
                "question", Map.of("type", "string", "minLength", 1, "maxLength", 1000, "description", "One self-contained question about the decision or missing information."),
                "options", Map.of("type", "array", "maxItems", 4, "items", option, "description", "Prefer 2 to 4 evidence-based choices. Use [] if choices cannot be offered. Free-text answers are always available.")),
                "required", List.of("question", "options"), "additionalProperties", false);
        return new AgentToolAccess.Tool(NAME,
                "Ask the user one question in the conversation and wait for their answer. Use when an unresolved ambiguity, missing information or choice affects the result. Prefer concrete options based on actual findings, explain their implications, and let the user choose a direction instead of locating the answer for you. Continue using the returned answer. Only one question can be pending per session.",
                schema, "Ask for a necessary user decision with selectable options and free-text input.",
                List.of("Use tools to gather available evidence before asking. Continue autonomously when the evidence is sufficient.",
                        "Do not invent candidates, assume an answer, or treat waiting/cancellation as selecting an option."));
    }

    public AgentQuestion.Result execute(String sessionId, String runId, String toolCallId, Long userId,
            Map<String, Object> arguments, AgentRuntimeEventSink sink, BooleanSupplier active) {
        AgentQuestion.Request request;
        try { request = json.convertValue(arguments, AgentQuestion.Request.class); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid askUserQuestion arguments; provide question and options containing id, label and optional description.", error); }
        var question = new AgentQuestion(UUID.randomUUID().toString(), sessionId, runId, toolCallId, request);
        return new AgentQuestion.Result(true, questions.awaitAnswer(question, userId, sink, active));
    }
}
