package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeHealth;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSnapshot;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeHandleRegistryTest {

    @Test
    void registersGetsAndPreciselyRemovesHandles() {
        AgentRuntimeHandleRegistry registry = new AgentRuntimeHandleRegistry();
        RecordingHandle handle = new RecordingHandle("external-one");

        registry.register("session-one", handle);

        assertSame(handle, registry.get("session-one"));
        assertFalse(registry.remove("session-one", new RecordingHandle("other")));
        assertTrue(registry.remove("session-one", handle));
        assertTrue(handle.closed);
        assertNull(registry.get("session-one"));
    }

    @Test
    void duplicateRegistrationClosesTheRejectedHandle() {
        AgentRuntimeHandleRegistry registry = new AgentRuntimeHandleRegistry();
        RecordingHandle first = new RecordingHandle("external-one");
        RecordingHandle duplicate = new RecordingHandle("external-two");
        registry.register("session-one", first);

        assertThrows(IllegalStateException.class, () -> registry.register("session-one", duplicate));

        assertSame(first, registry.get("session-one"));
        assertFalse(first.closed);
        assertTrue(duplicate.closed);
    }

    @Test
    void closeAllClosesEveryRegisteredHandle() {
        AgentRuntimeHandleRegistry registry = new AgentRuntimeHandleRegistry();
        RecordingHandle first = new RecordingHandle("external-one");
        RecordingHandle second = new RecordingHandle("external-two");
        registry.register("session-one", first);
        registry.register("session-two", second);

        registry.closeAll();

        assertEquals(0, registry.size());
        assertTrue(first.closed);
        assertTrue(second.closed);
        RecordingHandle rejected = new RecordingHandle("external-three");
        assertThrows(IllegalStateException.class, () -> registry.register("session-three", rejected));
        assertTrue(rejected.closed);
    }

    private static final class RecordingHandle implements AgentRuntimeSessionHandle {

        private final AgentRuntimeSessionRef session;
        private boolean closed;

        private RecordingHandle(String externalSessionId) {
            session = new AgentRuntimeSessionRef(externalSessionId, null);
        }

        @Override
        public AgentRuntimeSessionRef session() {
            return session;
        }

        @Override
        public CompletionStage<AgentRuntimeRunRef> startRun(AgentRuntimeRunRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> cancel(AgentRuntimeCancelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<AgentRuntimeSnapshot> snapshot() {
            return CompletableFuture.completedFuture(
                    new AgentRuntimeSnapshot(session, AgentRuntimeHealth.READY, null));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void idleReclamationPreservesActiveHandlesAndAllowsNewSessions() {
        AgentRuntimeHandleRegistry registry = new AgentRuntimeHandleRegistry();
        RecordingHandle idle = new RecordingHandle("idle");
        RecordingHandle active = new RecordingHandle("active");
        registry.register("idle", idle);
        registry.register("active", active);
        registry.closeIdle("idle"::equals);
        assertTrue(idle.closed);
        assertFalse(active.closed);
        assertSame(active, registry.get("active"));
        registry.register("new", new RecordingHandle("new"));
        assertEquals(2, registry.size());
    }
}
