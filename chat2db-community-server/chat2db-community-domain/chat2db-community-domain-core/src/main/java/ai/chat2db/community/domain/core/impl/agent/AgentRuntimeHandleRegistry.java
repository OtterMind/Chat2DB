package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentRuntimeHandleRegistry {

    private final Map<String, AgentRuntimeSessionHandle> handles = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentRuntimeSessionHandle get(String sessionId) {
        return handles.get(requireSessionId(sessionId));
    }

    public void register(String sessionId, AgentRuntimeSessionHandle handle) {
        String id = requireSessionId(sessionId);
        Objects.requireNonNull(handle, "handle");
        if (closed.get()) {
            handle.close();
            throw new IllegalStateException("Agent runtime handle registry is closed");
        }
        AgentRuntimeSessionHandle existing = handles.putIfAbsent(id, handle);
        if (existing != null) {
            handle.close();
            throw new IllegalStateException("Agent runtime session is already active: " + id);
        }
        if (closed.get() && handles.remove(id, handle)) {
            handle.close();
            throw new IllegalStateException("Agent runtime handle registry is closed");
        }
    }

    public boolean remove(String sessionId, AgentRuntimeSessionHandle expected) {
        String id = requireSessionId(sessionId);
        Objects.requireNonNull(expected, "expected");
        if (!handles.remove(id, expected)) {
            return false;
        }
        expected.close();
        return true;
    }

    public void closeAll() {
        closed.set(true);
        for (Map.Entry<String, AgentRuntimeSessionHandle> entry : new ArrayList<>(handles.entrySet())) {
            remove(entry.getKey(), entry.getValue());
        }
    }

    public int size() {
        return handles.size();
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }
}
