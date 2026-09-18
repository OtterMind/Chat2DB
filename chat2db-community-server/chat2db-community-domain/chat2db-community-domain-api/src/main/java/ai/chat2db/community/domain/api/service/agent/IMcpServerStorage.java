package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import java.util.List;

/** Persistence for the user-editable MCP server file. */
public interface IMcpServerStorage {

    /** Absolute path of the configuration file. */
    String path();

    List<McpServerConfig> load();

    void save(List<McpServerConfig> servers);
}
