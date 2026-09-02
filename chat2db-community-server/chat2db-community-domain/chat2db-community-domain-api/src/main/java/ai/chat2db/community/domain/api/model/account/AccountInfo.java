package ai.chat2db.community.domain.api.model.account;

import lombok.Data;

@Data
public class AccountInfo {
    private String user;
    private String host;
    private String displayName;
    private String authenticationPlugin;
    private Boolean locked;
    private Boolean passwordExpired;
    private String passwordExpirePolicy;
    private String passwordLastChanged;
    private Integer passwordLifetime;
    private Integer maxQueriesPerHour;
    private Integer maxUpdatesPerHour;
    private Integer maxConnectionsPerHour;
    private Integer maxUserConnections;
}
