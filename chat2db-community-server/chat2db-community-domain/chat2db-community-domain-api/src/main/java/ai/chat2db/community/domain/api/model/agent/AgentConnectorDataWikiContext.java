package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

/**
 * Read-only DataWiki summary exposed to one authorized Connector Session.
 */
@Data
public class AgentConnectorDataWikiContext {

    private String id;

    private String name;

    private String description;

    private Integer tableCount;

    private Integer dataSourceCount;

    private Integer maxRows;

    private Integer timeoutSeconds;

    private String approvalMode;

    private Boolean allowProduction;
}
