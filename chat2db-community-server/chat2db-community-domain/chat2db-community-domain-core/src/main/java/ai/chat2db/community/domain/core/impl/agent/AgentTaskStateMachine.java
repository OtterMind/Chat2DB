package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

final class AgentTaskStateMachine {

    private static final Map<AgentTaskStatusEnum, Set<AgentTaskStatusEnum>> TRANSITIONS = transitions();

    private AgentTaskStateMachine() {
    }

    static void requireTransition(AgentTaskStatusEnum source, AgentTaskStatusEnum target) {
        if (source == null || target == null || !TRANSITIONS.getOrDefault(source, Set.of()).contains(target)) {
            throw new IllegalStateException("illegal task status transition: " + source + " -> " + target);
        }
    }

    private static Map<AgentTaskStatusEnum, Set<AgentTaskStatusEnum>> transitions() {
        Map<AgentTaskStatusEnum, Set<AgentTaskStatusEnum>> transitions = new EnumMap<>(AgentTaskStatusEnum.class);
        transitions.put(AgentTaskStatusEnum.BACKLOG,
                EnumSet.of(AgentTaskStatusEnum.TODO, AgentTaskStatusEnum.CANCELLED));
        transitions.put(AgentTaskStatusEnum.TODO,
                EnumSet.of(AgentTaskStatusEnum.IN_PROGRESS, AgentTaskStatusEnum.BLOCKED,
                        AgentTaskStatusEnum.CANCELLED));
        transitions.put(AgentTaskStatusEnum.IN_PROGRESS,
                EnumSet.of(AgentTaskStatusEnum.WAITING_APPROVAL, AgentTaskStatusEnum.IN_REVIEW,
                        AgentTaskStatusEnum.BLOCKED,
                        AgentTaskStatusEnum.CANCELLED));
        transitions.put(AgentTaskStatusEnum.WAITING_APPROVAL,
                EnumSet.of(AgentTaskStatusEnum.IN_PROGRESS, AgentTaskStatusEnum.BLOCKED,
                        AgentTaskStatusEnum.CANCELLED));
        transitions.put(AgentTaskStatusEnum.IN_REVIEW,
                EnumSet.of(AgentTaskStatusEnum.IN_PROGRESS, AgentTaskStatusEnum.DONE,
                        AgentTaskStatusEnum.BLOCKED, AgentTaskStatusEnum.CANCELLED));
        transitions.put(AgentTaskStatusEnum.BLOCKED,
                EnumSet.of(AgentTaskStatusEnum.IN_PROGRESS, AgentTaskStatusEnum.CANCELLED));
        transitions.put(AgentTaskStatusEnum.DONE, EnumSet.noneOf(AgentTaskStatusEnum.class));
        transitions.put(AgentTaskStatusEnum.CANCELLED, EnumSet.noneOf(AgentTaskStatusEnum.class));
        return transitions;
    }
}
