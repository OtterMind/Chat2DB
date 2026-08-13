package ai.chat2db.community.domain.core.impl.agent.runtime;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.service.agent.runtime.AgentRuntime;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Component
public class AgentRuntimeRegistry {

    private final Map<AgentRuntimeTypeEnum, AgentRuntime> runtimes;

    public AgentRuntimeRegistry(List<AgentRuntime> runtimes) {
        Map<AgentRuntimeTypeEnum, AgentRuntime> registered = new EnumMap<>(AgentRuntimeTypeEnum.class);
        for (AgentRuntime runtime : runtimes) {
            if (runtime == null || runtime.type() == null) {
                throw new IllegalStateException("agent runtime and runtime type are required");
            }
            AgentRuntime duplicate = registered.putIfAbsent(runtime.type(), runtime);
            if (duplicate != null) {
                throw new IllegalStateException("duplicate agent runtime registration: " + runtime.type());
            }
        }
        this.runtimes = Map.copyOf(registered);
    }

    public AgentRuntime require(AgentRuntimeTypeEnum type) {
        AgentRuntime runtime = runtimes.get(type);
        if (runtime == null) {
            throw new NoSuchElementException("agent runtime is not registered: " + type);
        }
        return runtime;
    }

    public List<AgentRuntimeTypeEnum> registeredTypes() {
        return runtimes.keySet().stream().sorted().toList();
    }
}
