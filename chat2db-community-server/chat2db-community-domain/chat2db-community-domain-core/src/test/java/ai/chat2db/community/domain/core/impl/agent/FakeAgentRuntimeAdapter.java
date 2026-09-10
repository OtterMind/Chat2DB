package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeAdapter;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeCapability;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeHealth;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeCapabilities;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionDeleteRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionResumeRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class FakeAgentRuntimeAdapter implements IAgentRuntimeAdapter {

    private final AgentRuntimeDescriptor descriptor;
    private final AgentRuntimeEnvironmentStatus environmentStatus;
    private String deletedSessionId;
    private int openSessionCount;
    private RuntimeException openFailure;
    private RuntimeException startFailure;
    private AgentEventType terminalEventOnStart;

    FakeAgentRuntimeAdapter(AgentRuntimeType runtimeType) {
        this(runtimeType, AgentRuntimeEnvironmentStatus.READY);
    }

    FakeAgentRuntimeAdapter(
            AgentRuntimeType runtimeType,
            AgentRuntimeEnvironmentStatus environmentStatus) {
        this.environmentStatus = environmentStatus;
        this.descriptor = new AgentRuntimeDescriptor(
                runtimeType,
                runtimeType.name(),
                "1.0.0",
                "fake-v1",
                new AgentRuntimeCapabilities(
                        Set.of(AgentRuntimeCapability.STREAMING, AgentRuntimeCapability.CANCELLATION),
                        1));
    }

    @Override
    public AgentRuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AgentRuntimeEnvironmentReport inspectEnvironment(AgentRuntimeEnvironmentRequest request) {
        return new AgentRuntimeEnvironmentReport(
                descriptor.type(),
                environmentStatus,
                descriptor.version(),
                request.operatingSystem(),
                request.architecture(),
                List.of("runtime"),
                Map.of(),
                LocalDateTime.of(2026, 9, 8, 22, 0));
    }

    @Override
    public IAgentRuntimeSessionHandle openSession(
            AgentRuntimeSessionOpenRequest request,
            IAgentRuntimeEventSink eventSink) {
        openSessionCount++;
        if (openFailure != null) {
            throw openFailure;
        }
        return new FakeSessionHandle(
                request.sessionId(), request.externalSessionId(), null, eventSink,
                startFailure, terminalEventOnStart);
    }

    @Override
    public IAgentRuntimeSessionHandle resumeSession(
            AgentRuntimeSessionResumeRequest request,
            IAgentRuntimeEventSink eventSink) {
        return new FakeSessionHandle(
                request.sessionId(),
                request.binding().externalSessionId(),
                request.binding().resumeReference(),
                eventSink,
                startFailure,
                terminalEventOnStart);
    }

    @Override
    public void deleteSession(AgentRuntimeSessionDeleteRequest request) {
        deletedSessionId = request.sessionId();
    }

    String deletedSessionId() {
        return deletedSessionId;
    }

    int openSessionCount() {
        return openSessionCount;
    }

    void failOpenWith(RuntimeException failure) {
        openFailure = failure;
    }

    void failStartWith(RuntimeException failure) {
        startFailure = failure;
    }

    void emitTerminalEventOnStart(AgentEventType type) {
        terminalEventOnStart = type;
    }

    private static final class FakeSessionHandle implements IAgentRuntimeSessionHandle {

        private final String sessionId;
        private final AgentRuntimeSessionRef session;
        private final IAgentRuntimeEventSink eventSink;
        private RuntimeException startFailure;
        private final AgentEventType terminalEventOnStart;
        private AgentRuntimeHealth health = AgentRuntimeHealth.READY;
        private String activeRunId;

        private FakeSessionHandle(
                String sessionId,
                String externalSessionId,
                String resumeReference,
                IAgentRuntimeEventSink eventSink,
                RuntimeException startFailure,
                AgentEventType terminalEventOnStart) {
            this.sessionId = sessionId;
            this.session = new AgentRuntimeSessionRef(externalSessionId, resumeReference);
            this.eventSink = eventSink;
            this.startFailure = startFailure;
            this.terminalEventOnStart = terminalEventOnStart;
        }

        @Override
        public AgentRuntimeSessionRef session() {
            return session;
        }

        @Override
        public CompletionStage<AgentRuntimeRunRef> startRun(AgentRuntimeRunRequest request) {
            if (!sessionId.equals(request.sessionId())) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Run belongs to another session"));
            }
            activeRunId = "external-" + request.runId();
            health = AgentRuntimeHealth.BUSY;
            emit(request.runId(), AgentEventType.RUN_STARTED);
            if (terminalEventOnStart != null) {
                emit(request.runId(), terminalEventOnStart);
            }
            if (startFailure != null) {
                RuntimeException failure = startFailure;
                startFailure = null;
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new AgentRuntimeRunRef(request.runId(), activeRunId));
        }

        @Override
        public CompletionStage<Void> cancel(AgentRuntimeCancelRequest request) {
            if (!sessionId.equals(request.sessionId()) || !request.externalRunId().equals(activeRunId)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown active run"));
            }
            emit(request.runId(), AgentEventType.RUN_CANCELLED);
            activeRunId = null;
            health = AgentRuntimeHealth.READY;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<AgentRuntimeSnapshot> snapshot() {
            return CompletableFuture.completedFuture(new AgentRuntimeSnapshot(session, health, activeRunId));
        }

        @Override
        public void close() {
            activeRunId = null;
            health = AgentRuntimeHealth.STOPPED;
        }

        private void emit(String runId, AgentEventType type) {
            eventSink.emit(new AgentRuntimeEvent(
                    type.name().toLowerCase() + "-event",
                    sessionId,
                    runId,
                    type,
                    Map.of(),
                    LocalDateTime.of(2026, 9, 8, 22, 0)));
        }
    }
}
