package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

/**
 * Task-scoped MCP endpoint descriptor returned with a Runtime claim.
 * Secrets are deliberately excluded; the bearer token is carried separately
 * by the claim and injected into the Provider process environment by Daemon.
 */
@Data
public class AgentRuntimeMcpEndpoint {

    private String name;
    private String transport;
    private String path;
    private String bearerTokenEnvironmentVariable;
}
