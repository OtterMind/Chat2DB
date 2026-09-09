package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeInput;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;
import ai.chat2db.community.domain.api.service.agent.AgentService;
import ai.chat2db.community.domain.api.model.ai.AiSessionSummary;
import ai.chat2db.community.domain.api.service.ai.AiSessionFacadeService;
import ai.chat2db.community.web.api.adapter.agent.AgentHostEnvironmentProvider;
import ai.chat2db.community.web.api.model.request.agent.AgentRunCancelRequest;
import ai.chat2db.community.web.api.model.request.agent.AgentRunStartRequest;
import ai.chat2db.community.web.api.model.request.agent.AgentSessionCreateRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentControllerTest {

    private static final Long USER_ID = 42L;
    private final RecordingAgentService service = new RecordingAgentService();
    private final AgentController controller = new AgentController(
            service, () -> USER_ID, new AgentHostEnvironmentProvider("5.3.0"), new SessionFacade());

    @Test
    void createsOnlyV2SessionsForCurrentUser() {
        AgentSessionCreateRequest request = new AgentSessionCreateRequest(
                2, "Session", definition());

        assertEquals(service.session, controller.createSession(request).getData());
        assertEquals(USER_ID, service.createCommand.userId());
        assertThrows(IllegalArgumentException.class,
                () -> controller.createSession(new AgentSessionCreateRequest(
                        1, "Session", definition())));
    }

    @Test
    void routesRunsAndEventsWithCurrentIdentity() {
        AgentModelSnapshot model = model();
        AgentRuntimeInput input = new AgentRuntimeInput("hello", List.of());

        AgentRun started = controller.startRun(
                        "session-one", new AgentRunStartRequest(model, input, "request-one"))
                .toCompletableFuture().join().getData();
        AgentRun cancelled = controller.cancelRun(
                        started.id(), new AgentRunCancelRequest("session-one"))
                .toCompletableFuture().join().getData();

        assertEquals(USER_ID, service.startCommand.userId());
        assertEquals(USER_ID, service.cancelCommand.userId());
        assertEquals(started, cancelled);
        assertEquals(AgentEventType.RUN_STARTED,
                controller.listEvents("session-one", 0, 20).getData().get(0).type());
        assertEquals(USER_ID, service.eventUserId);
    }

    private AgentDefinition definition() {
        return new AgentDefinition(
                "default", "Default", null, "Help", AgentRuntimeType.PI, "model", 1);
    }

    private AgentModelSnapshot model() {
        return new AgentModelSnapshot("model", 1, "openai", "gpt-test", 1000, 100);
    }

    private final class RecordingAgentService implements AgentService {
        private final LocalDateTime now = LocalDateTime.of(2026, 9, 9, 0, 0);
        private final AgentSession session = new AgentSession(
                2, "session-one", USER_ID, definition(),
                new AgentRuntimeBinding(AgentRuntimeType.PI, "1", "1", "external", null, 1),
                AgentSessionStatus.READY, "Session", 0, now, now);
        private AgentSessionCreateCommand createCommand;
        private AgentRunStartCommand startCommand;
        private AgentRunCancelCommand cancelCommand;
        private Long eventUserId;

        @Override public AgentSession createSession(AgentSessionCreateCommand command) {
            createCommand = command;
            return session;
        }
        @Override public AgentSession getSession(String sessionId, Long userId) { return session; }
        @Override public List<AgentSession> listSessions(Long userId) { return List.of(session); }
        @Override public CompletionStage<AgentRun> startRun(AgentRunStartCommand command) {
            startCommand = command;
            return CompletableFuture.completedFuture(run());
        }
        @Override public CompletionStage<AgentRun> cancelRun(AgentRunCancelCommand command) {
            cancelCommand = command;
            return CompletableFuture.completedFuture(run());
        }
        @Override public List<AgentEvent> listEvents(
                String sessionId, Long userId, long afterSequence, int limit) {
            eventUserId = userId;
            return List.of(new AgentEvent(
                    "event", sessionId, "run-one", 1, AgentEventType.RUN_STARTED, Map.of(), now));
        }
        @Override public AgentSession renameSession(String sessionId, Long userId, String title) {
            return session;
        }
        @Override public void deleteSession(String sessionId, Long userId) { }
        private AgentRun run() {
            return new AgentRun(
                    "run-one", "session-one", AgentRunStatus.RUNNING, model(),
                    "message", "request-one", "external-run", 1, 1, null, null);
        }
    }

    private final class SessionFacade implements AiSessionFacadeService {
        @Override public List<AiSessionSummary> listSessions(Long userId) {
            return List.of(summary());
        }
        @Override public AiSessionSummary getSession(String sessionId, Long userId, int sessionVersion) {
            return summary();
        }
        private AiSessionSummary summary() {
            return new AiSessionSummary(
                    "session-one", "Session", 2, AgentRuntimeType.PI, AgentSessionStatus.READY,
                    LocalDateTime.of(2026, 9, 9, 0, 0), LocalDateTime.of(2026, 9, 9, 0, 0));
        }
    }
}
