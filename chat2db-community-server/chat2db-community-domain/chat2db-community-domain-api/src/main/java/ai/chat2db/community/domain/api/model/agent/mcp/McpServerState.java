package ai.chat2db.community.domain.api.model.agent.mcp;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.enums.agent.McpTransport;
import java.util.List;

/** Read-only view of one configured server, as the management tools report it. */
public record McpServerState(
        String name,
        McpTransport transport,
        boolean enabled,
        McpToolPolicy policy,
        List<String> environmentKeys,
        List<String> headerNames,
        int toolCount,
        boolean commandApproved,
        String updatedAt) {

    public McpServerState {
        environmentKeys = environmentKeys == null ? List.of() : List.copyOf(environmentKeys);
        headerNames = headerNames == null ? List.of() : List.copyOf(headerNames);
    }

    public static McpServerState from(McpServerConfig config) {
        return new McpServerState(config.name(), config.transport(), config.enabled(), config.policy(),
                config.environmentKeys(), config.headerNames(), config.tools().size(),
                config.approvedCommandHash() != null, config.updatedAt());
    }
}
