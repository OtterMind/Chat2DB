package ai.chat2db.community.domain.api.model.account;

import lombok.Data;

@Data
public class AccountInfo {
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
