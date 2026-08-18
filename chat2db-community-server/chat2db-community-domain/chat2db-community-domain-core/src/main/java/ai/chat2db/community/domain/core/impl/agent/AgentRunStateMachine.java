package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

final class AgentRunStateMachine {

    private static final Map<AgentRunStatusEnum, Set<AgentRunStatusEnum>> TRANSITIONS = transitions();

    private AgentRunStateMachine() {
    }

    static void requireTransition(AgentRunStatusEnum source, AgentRunStatusEnum target) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("run source and target status are required");
        }
        if (!TRANSITIONS.getOrDefault(source, Set.of()).contains(target)) {
            throw new IllegalStateException("invalid run status transition: " + source + " -> " + target);
        }
    }

    static boolean terminal(AgentRunStatusEnum status) {
        return status == AgentRunStatusEnum.COMPLETED
                || status == AgentRunStatusEnum.FAILED
                || status == AgentRunStatusEnum.CANCELLED
                || status == AgentRunStatusEnum.UNKNOWN;
    }

    private static Map<AgentRunStatusEnum, Set<AgentRunStatusEnum>> transitions() {
        Map<AgentRunStatusEnum, Set<AgentRunStatusEnum>> result = new EnumMap<>(AgentRunStatusEnum.class);
        result.put(AgentRunStatusEnum.QUEUED, EnumSet.of(
                AgentRunStatusEnum.DISPATCHED,
                AgentRunStatusEnum.RUNNING,
                AgentRunStatusEnum.CANCELLED));
        result.put(AgentRunStatusEnum.DISPATCHED, EnumSet.of(
                AgentRunStatusEnum.RUNNING,
                AgentRunStatusEnum.FAILED,
                AgentRunStatusEnum.CANCELLED,
                AgentRunStatusEnum.UNKNOWN));
        result.put(AgentRunStatusEnum.RUNNING, EnumSet.of(
                AgentRunStatusEnum.WAITING_APPROVAL,
                AgentRunStatusEnum.COMPLETED,
                AgentRunStatusEnum.FAILED,
                AgentRunStatusEnum.CANCELLED,
                AgentRunStatusEnum.UNKNOWN));
        result.put(AgentRunStatusEnum.WAITING_APPROVAL, EnumSet.of(
                AgentRunStatusEnum.QUEUED,
                AgentRunStatusEnum.RUNNING,
                AgentRunStatusEnum.FAILED,
                AgentRunStatusEnum.CANCELLED));
        return result;
    }
}
