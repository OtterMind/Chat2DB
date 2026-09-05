package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

@Data
public class AccountResponse {
    private String user;
    private String host;
    private String displayName;
    private String authenticationPlugin;
    private String tlsRequirement;
    private String tlsCipher;
    private String tlsIssuer;
    private String tlsSubject;
    private Boolean locked;
}
