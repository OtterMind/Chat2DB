package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

@Data
public class AccountResponse {
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
