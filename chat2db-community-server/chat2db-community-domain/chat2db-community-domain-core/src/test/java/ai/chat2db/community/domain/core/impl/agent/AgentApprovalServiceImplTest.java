package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalScope;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalStorage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentApprovalServiceImplTest {
    @Test
    void decisionsRequireOwnershipAndCannotBeReused() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        AgentApprovalServiceImpl service = new AgentApprovalServiceImpl(storage);
        CountDownLatch published = new CountDownLatch(1);
        var result = CompletableFuture.supplyAsync(() ->
                service.awaitDecision(approval(), 1L, published::countDown, () -> true));
        assertTrue(published.await(2, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> service.decide("session", "approval", 2L, true));
        service.decide("session", "approval", 1L, true);
        assertTrue(result.get(2, TimeUnit.SECONDS));
        assertEquals(AgentApprovalStatus.APPROVED, storage.row.status());
        assertThrows(IllegalStateException.class, () -> service.decide("session", "approval", 1L, true));
    }

    @Test
    void stoppingRunCancelsPendingApproval() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        AgentApprovalServiceImpl service = new AgentApprovalServiceImpl(storage);
        CountDownLatch published = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean(true);
        var result = CompletableFuture.supplyAsync(() ->
                service.awaitDecision(approval(), 1L, published::countDown, active::get));
        assertTrue(published.await(2, TimeUnit.SECONDS));
        active.set(false);
        assertFalse(result.get(2, TimeUnit.SECONDS));
        assertEquals(AgentApprovalStatus.CANCELLED, storage.row.status());
    }

    private AgentApproval approval() {
        return new AgentApproval("approval", "session", "run", "tool", AgentApprovalStatus.PENDING,
                AgentApprovalScope.ONCE, "0".repeat(64), LocalDateTime.now().plusSeconds(10));
    }

    private static class MemoryStorage implements AgentApprovalStorage {
        volatile AgentApproval row;
        @Override public AgentApproval create(AgentApproval value, Long userId) { row = value; return value; }
        @Override public AgentApproval get(String sessionId, String id, Long userId) {
            return userId == 1L && row != null && row.sessionId().equals(sessionId) ? row : null;
        }
        @Override public List<AgentApproval> list(String sessionId, Long userId) { return List.of(row); }
        @Override public synchronized boolean compareAndSet(AgentApproval value, AgentApprovalStatus status, Long userId) {
            if (row == null || row.status() != status) return false;
            row = value;
            return true;
        }
    }
}
