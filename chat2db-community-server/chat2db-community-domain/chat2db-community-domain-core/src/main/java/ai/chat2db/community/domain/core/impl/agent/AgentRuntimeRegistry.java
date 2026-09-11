package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeAdapter;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AgentRuntimeRegistry {

    private final Map<AgentRuntimeType, IAgentRuntimeAdapter> adapters;

    public AgentRuntimeRegistry(List<IAgentRuntimeAdapter> adapters) {
        Map<AgentRuntimeType, IAgentRuntimeAdapter> registered = new LinkedHashMap<>();
        for (IAgentRuntimeAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            AgentRuntimeType runtimeType = adapter.descriptor().type();
            IAgentRuntimeAdapter previous = registered.putIfAbsent(runtimeType, adapter);
            if (previous != null) {
                throw new IllegalStateException("Duplicate agent runtime adapter: " + runtimeType);
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public IAgentRuntimeAdapter get(AgentRuntimeType runtimeType) {
        return adapters.get(Objects.requireNonNull(runtimeType, "runtimeType"));
    }

    public IAgentRuntimeAdapter require(AgentRuntimeType runtimeType) {
        IAgentRuntimeAdapter adapter = get(runtimeType);
        if (adapter == null) {
            throw new AgentRuntimeUnavailableException(runtimeType.name());
        }
        return adapter;
    }

    public List<IAgentRuntimeAdapter> list() {
        return adapters.values().stream()
                .sorted((left, right) -> left.descriptor().type().compareTo(right.descriptor().type()))
                .toList();
    }
}
