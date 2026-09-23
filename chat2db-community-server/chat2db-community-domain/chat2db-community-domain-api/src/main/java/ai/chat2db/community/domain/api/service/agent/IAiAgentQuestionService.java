package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.interaction.AgentQuestion;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface IAiAgentQuestionService {
    AgentQuestion.Answer awaitAnswer(AgentQuestion question, Long userId, IAgentRuntimeEventSink sink, BooleanSupplier active);
    AgentQuestion.Answer answer(String sessionId, String questionId, Long userId, AgentQuestion.Response response);
    void cancel(String sessionId, String runId, Long userId);
    List<AgentQuestion> pending(String sessionId, Long userId);
}
