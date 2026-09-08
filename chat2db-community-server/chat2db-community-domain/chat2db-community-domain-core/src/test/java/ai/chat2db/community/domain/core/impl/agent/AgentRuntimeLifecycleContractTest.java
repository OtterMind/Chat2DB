package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeHealth;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeInput;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionDeleteRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionResumeRequest;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeLifecycleContractTest {

    @Test
    void opensRunsCancelsAndSnapshotsWithoutProductEventSequences() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        List<AgentRuntimeEvent> events = new ArrayList<>();
        AgentRuntimeSessionHandle handle = adapter.openSession(
                new AgentRuntimeSessionOpenRequest("session", "external-session", null, model()),
                events::add);

        AgentRuntimeRunRef run = handle.startRun(new AgentRuntimeRunRequest(
                        "session",
                        "run",
                        model(),
                        new AgentRuntimeInput("hello", List.of()),
                        "idempotency-key"))
                .toCompletableFuture()
                .join();

        assertEquals("external-run", run.externalRunId());
        assertEquals(AgentRuntimeHealth.BUSY,
                handle.snapshot().toCompletableFuture().join().health());
        handle.cancel(new AgentRuntimeCancelRequest("session", "run", run.externalRunId()))
                .toCompletableFuture()
                .join();
        assertEquals(List.of(AgentEventType.RUN_STARTED, AgentEventType.RUN_CANCELLED),
                events.stream().map(AgentRuntimeEvent::type).toList());
        assertEquals(AgentRuntimeHealth.READY,
                handle.snapshot().toCompletableFuture().join().health());

        handle.close();
        assertEquals(AgentRuntimeHealth.STOPPED,
                handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void resumesAndDeletesUsingTheRuntimeBinding() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        AgentRuntimeBinding binding = new AgentRuntimeBinding(
                AgentRuntimeType.PI, "1.0.0", "fake-v1", "external-session", "resume-ref", 1);

        AgentRuntimeSessionHandle handle = adapter.resumeSession(
                new AgentRuntimeSessionResumeRequest("session", binding, model()),
                event -> {
                });
        adapter.deleteSession(new AgentRuntimeSessionDeleteRequest("session", binding));

        assertEquals("resume-ref", handle.session().resumeReference());
        assertEquals("session", adapter.deletedSessionId());
    }

    @Test
    void reportsEnvironmentAndRejectsEmptyRunInput() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);

        assertEquals(AgentRuntimeEnvironmentStatus.READY, adapter.inspectEnvironment(
                new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64")).status());
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeInput(" ", List.of()));
    }

    private AgentModelSnapshot model() {
        return new AgentModelSnapshot("model-config", 1, "openai", "gpt-test", 128000, 4096);
    }
}
