package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeAdapter;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AgentRuntimeRegistry {

    private final Map<AgentRuntimeType, AgentRuntimeAdapter> adapters;

    public AgentRuntimeRegistry(List<AgentRuntimeAdapter> adapters) {
        Map<AgentRuntimeType, AgentRuntimeAdapter> registered = new LinkedHashMap<>();
        for (AgentRuntimeAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            AgentRuntimeType runtimeType = adapter.descriptor().type();
            AgentRuntimeAdapter previous = registered.putIfAbsent(runtimeType, adapter);
            if (previous != null) {
                throw new IllegalStateException("Duplicate agent runtime adapter: " + runtimeType);
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public AgentRuntimeAdapter get(AgentRuntimeType runtimeType) {
        return adapters.get(Objects.requireNonNull(runtimeType, "runtimeType"));
    }

    public AgentRuntimeAdapter require(AgentRuntimeType runtimeType) {
        AgentRuntimeAdapter adapter = get(runtimeType);
        if (adapter == null) {
            throw new AgentRuntimeUnavailableException(runtimeType.name());
        }
        return adapter;
    }

    public List<AgentRuntimeAdapter> list() {
        return adapters.values().stream()
                .sorted((left, right) -> left.descriptor().type().compareTo(right.descriptor().type()))
                .toList();
    }
}
