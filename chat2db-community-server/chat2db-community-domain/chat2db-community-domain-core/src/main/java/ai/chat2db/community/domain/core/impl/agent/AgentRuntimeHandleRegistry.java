package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class AgentRuntimeHandleRegistry {

    private final Map<String, IAgentRuntimeSessionHandle> handles = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public IAgentRuntimeSessionHandle get(String sessionId) {
        return handles.get(requireSessionId(sessionId));
    }

    public void register(String sessionId, IAgentRuntimeSessionHandle handle) {
        String id = requireSessionId(sessionId);
        Objects.requireNonNull(handle, "handle");
        if (closed.get()) {
            handle.close();
            throw new IllegalStateException("Agent runtime handle registry is closed");
        }
        IAgentRuntimeSessionHandle existing = handles.putIfAbsent(id, handle);
        if (existing != null) {
            handle.close();
            throw new IllegalStateException("Agent runtime session is already active: " + id);
        }
        if (closed.get() && handles.remove(id, handle)) {
            handle.close();
            throw new IllegalStateException("Agent runtime handle registry is closed");
        }
    }

    public boolean remove(String sessionId, IAgentRuntimeSessionHandle expected) {
        String id = requireSessionId(sessionId);
        Objects.requireNonNull(expected, "expected");
        if (!handles.remove(id, expected)) {
            return false;
        }
        expected.close();
        return true;
    }

    public boolean close(String sessionId) {
        String id = requireSessionId(sessionId);
        IAgentRuntimeSessionHandle handle = handles.remove(id);
        if (handle == null) {
            return false;
        }
        handle.close();
        return true;
    }

    public void closeAll() {
        closed.set(true);
        for (Map.Entry<String, IAgentRuntimeSessionHandle> entry : new ArrayList<>(handles.entrySet())) {
            remove(entry.getKey(), entry.getValue());
        }
    }

    public int size() {
        return handles.size();
    }

    public void closeIdle(java.util.function.Predicate<String> isIdle) {
        for (var entry : new ArrayList<>(handles.entrySet())) {
            if (isIdle.test(entry.getKey()) && remove(entry.getKey(), entry.getValue())) {
                ai.chat2db.community.tools.util.AgentTrace.record("runtime.idle.closed", entry.getKey(), null, Map.of());
            }
        }
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }
}
