package ai.chat2db.community.domain.api.model.account;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class AccountOperationRequest {
    @NotBlank
    private String actionType;
    @NotBlank
    private String user;
    private String host;
    private String scope;
    private String databaseName;
    private String tableName;
    private List<String> privileges;
    private Boolean grantOption;
    private String password;
    private String previewToken;
    private String authPlugin;
    private String tlsRequirement;
    private String tlsCipher;
    private String tlsIssuer;
    private String tlsSubject;
}
