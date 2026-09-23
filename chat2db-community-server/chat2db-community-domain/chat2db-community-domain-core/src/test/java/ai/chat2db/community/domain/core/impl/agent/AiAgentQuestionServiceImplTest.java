package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.model.agent.interaction.AgentQuestion;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentQuestionServiceImplTest {
    @Test
    void waitsForARealChoiceAndRecordsItOnceBeforeResuming() throws Exception {
        var service = new AiAgentQuestionServiceImpl();
        var events = new CopyOnWriteArrayList<AgentRuntimeEvent>();
        var requested = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> service.awaitAnswer(question("q1"), 1L, event -> {
                events.add(event); if (event.type() == AgentEventType.QUESTION_REQUESTED) requested.countDown();
            }, () -> true));
            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertEquals("q1", service.pending("session", 1L).get(0).id());
            assertTrue(service.pending("session", 2L).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> service.answer("session", "q1", 2L, new AgentQuestion.Response("a", null)));
            assertThrows(IllegalArgumentException.class, () -> service.answer("session", "wrong", 1L, new AgentQuestion.Response("a", null)));
            assertThrows(IllegalArgumentException.class, () -> service.answer("session", "q1", 1L, new AgentQuestion.Response("missing", null)));
            assertFalse(task.isDone());
            var answer = service.answer("session", "q1", 1L, new AgentQuestion.Response("b", null));
            assertEquals("Second choice", answer.optionLabel());
            assertEquals(answer, task.get(2, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> service.answer("session", "q1", 1L, new AgentQuestion.Response("a", null)));
            assertEquals(List.of(AgentEventType.QUESTION_REQUESTED, AgentEventType.QUESTION_ANSWERED), events.stream().map(AgentRuntimeEvent::type).toList());
            assertTrue(service.pending("session", 1L).isEmpty());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void acceptsFreeTextAndRejectsAnEmptyAnswer() throws Exception {
        var service = new AiAgentQuestionServiceImpl(); var requested = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> service.awaitAnswer(question("q2"), 1L, event -> requested.countDown(), () -> true));
            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> service.answer("session", "q2", 1L, new AgentQuestion.Response(null, " ")));
            service.answer("session", "q2", 1L, new AgentQuestion.Response(null, "  自由回答  "));
            var answer = task.get(2, TimeUnit.SECONDS);
            assertNull(answer.optionId()); assertEquals("自由回答", answer.text());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void cancellationClosesTheQuestionAndRejectsLateAnswers() throws Exception {
        var service = new AiAgentQuestionServiceImpl(); var requested = new CountDownLatch(1);
        var active = new AtomicBoolean(true); var events = new CopyOnWriteArrayList<AgentRuntimeEvent>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> service.awaitAnswer(question("q3"), 1L, event -> {
                events.add(event); requested.countDown();
            }, active::get));
            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> service.awaitAnswer(question("q4"), 1L, event -> {}, active::get));
            service.cancel("session", "run", 1L);
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS)).getCause());
            assertThrows(IllegalArgumentException.class, () -> service.answer("session", "q3", 1L, new AgentQuestion.Response("a", null)));
            assertEquals(List.of(AgentEventType.QUESTION_REQUESTED, AgentEventType.QUESTION_CLOSED), events.stream().map(AgentRuntimeEvent::type).toList());
            assertTrue(service.pending("session", 1L).isEmpty());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void rejectsDuplicateOptionIdsBeforePublishing() {
        var service = new AiAgentQuestionServiceImpl();
        var request = new AgentQuestion.Request("Choose", List.of(new AgentQuestion.Option("a", "One", null), new AgentQuestion.Option("a", "Two", null)));
        assertThrows(IllegalArgumentException.class, () -> service.awaitAnswer(new AgentQuestion("q", "session", "run", "tool", request), 1L,
                event -> fail("Invalid questions must not be published"), () -> true));
    }

    private AgentQuestion question(String id) {
        return new AgentQuestion(id, "session", "run", "tool", new AgentQuestion.Request("Which direction?",
                List.of(new AgentQuestion.Option("a", "First choice", null), new AgentQuestion.Option("b", "Second choice", "Another direction"))));
    }
}
