package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.interaction.AgentQuestion;
import ai.chat2db.community.domain.api.service.agent.IAiAgentQuestionService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.tools.util.AgentTrace;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

@Service
public class AiAgentQuestionServiceImpl implements IAiAgentQuestionService {
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Override
    public AgentQuestion.Answer awaitAnswer(AgentQuestion question, Long userId, IAgentRuntimeEventSink sink, BooleanSupplier active) {
        validate(question.request());
        Pending item = new Pending(question, userId, sink, active);
        if (pending.putIfAbsent(question.sessionId(), item) != null) {
            throw new IllegalStateException("A question is already awaiting the user's answer. Ask one question at a time.");
        }
        try {
            if (!active.getAsBoolean()) throw new CancellationException("Agent run has stopped");
            emit(item, AgentEventType.QUESTION_REQUESTED, Map.of("questionId", question.id(),
                    "question", question.request().question(), "options", question.request().options()));
            AgentTrace.record("question.requested", question.sessionId(), question.runId(),
                    Map.of("questionId", question.id(), "toolCallId", question.toolCallId(), "options", question.request().options().size()));
            while (active.getAsBoolean()) {
                try {
                    var answer = item.answer.get(200, TimeUnit.MILLISECONDS);
                    if (!active.getAsBoolean()) throw new CancellationException("Agent run has stopped");
                    return answer;
                } catch (TimeoutException ignored) {
                    // Waiting is not an answer; check cancellation without choosing a default.
                }
            }
            throw new CancellationException("Agent run has stopped");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Question was interrupted");
        } catch (ExecutionException error) {
            throw new IllegalStateException("Question could not be answered", error.getCause());
        } finally {
            synchronized (item) {
                pending.remove(question.sessionId(), item);
                if (!item.answer.isDone()) {
                    item.answer.cancel(false);
                    emit(item, AgentEventType.QUESTION_CLOSED, Map.of("questionId", question.id()));
                    AgentTrace.record("question.closed", question.sessionId(), question.runId(), Map.of("questionId", question.id()));
                }
            }
        }
    }

    @Override
    public AgentQuestion.Answer answer(String sessionId, String questionId, Long userId, AgentQuestion.Response response) {
        Pending item = pending.get(sessionId);
        if (item == null || !item.userId.equals(userId) || !item.question.id().equals(questionId)) {
            throw new IllegalArgumentException("Question is unavailable or its run has stopped");
        }
        synchronized (item) {
            if (item.answer.isDone() || !item.active.getAsBoolean()) throw new IllegalStateException("Question is already closed");
            String optionId = response.optionId();
            String text = response.text() == null ? null : response.text().trim();
            if (text != null && text.length() > 4000) throw new IllegalArgumentException("Answer must not exceed 4000 characters");
            if (optionId == null && (text == null || text.isEmpty())) throw new IllegalArgumentException("Choose an option or enter an answer");
            AgentQuestion.Option option = optionId == null ? null : item.question.request().options().stream()
                    .filter(candidate -> candidate.id().equals(optionId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown question option"));
            var answer = new AgentQuestion.Answer(questionId, optionId, option == null ? null : option.label(), text);
            emit(item, AgentEventType.QUESTION_ANSWERED, Map.of("questionId", questionId, "answer", answer));
            AgentTrace.record("question.answered", sessionId, item.question.runId(),
                    Map.of("questionId", questionId, "optionId", Objects.toString(optionId, ""), "textCharacters", text == null ? 0 : text.length()));
            item.answer.complete(answer);
            return answer;
        }
    }

    @Override
    public void cancel(String sessionId, String runId, Long userId) {
        Pending item = pending.get(sessionId);
        if (item == null || !item.userId.equals(userId) || !item.question.runId().equals(runId)) return;
        synchronized (item) {
            if (item.answer.isDone()) return;
            emit(item, AgentEventType.QUESTION_CLOSED, Map.of("questionId", item.question.id()));
            AgentTrace.record("question.closed", sessionId, runId, Map.of("questionId", item.question.id()));
            item.answer.cancel(false);
        }
    }

    @Override
    public List<AgentQuestion> pending(String sessionId, Long userId) {
        Pending item = pending.get(sessionId);
        return item != null && item.userId.equals(userId) && !item.answer.isDone() && item.active.getAsBoolean()
                ? List.of(item.question) : List.of();
    }

    private void validate(AgentQuestion.Request request) {
        if (request == null || request.question() == null || request.question().isBlank() || request.question().length() > 1000) {
            throw new IllegalArgumentException("question must contain 1 to 1000 characters");
        }
        if (request.options() == null || request.options().size() > 4) throw new IllegalArgumentException("options must contain 0 to 4 choices");
        Set<String> ids = new HashSet<>();
        for (var option : request.options()) {
            if (option == null || option.id() == null || !option.id().matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(option.id())
                    || option.label() == null || option.label().isBlank() || option.label().length() > 120
                    || option.description() != null && option.description().length() > 300) {
                throw new IllegalArgumentException("Each option needs a unique id, a nonempty label up to 120 characters, and an optional description up to 300 characters");
            }
        }
    }

    private void emit(Pending item, AgentEventType type, Map<String, Object> payload) {
        var question = item.question;
        item.sink.emit(new AgentRuntimeEvent(UUID.randomUUID().toString(), question.sessionId(), question.runId(), type, payload, LocalDateTime.now()));
    }

    private record Pending(AgentQuestion question, Long userId, IAgentRuntimeEventSink sink, BooleanSupplier active,
                           CompletableFuture<AgentQuestion.Answer> answer) {
        Pending(AgentQuestion question, Long userId, IAgentRuntimeEventSink sink, BooleanSupplier active) {
            this(question, userId, sink, active, new CompletableFuture<>());
        }
    }
}
