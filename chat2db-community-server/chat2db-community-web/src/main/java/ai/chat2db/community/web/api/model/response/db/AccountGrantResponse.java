package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

import java.util.List;

@Data
public class AccountGrantResponse {
    private String source;
    private String scope;
    private String databaseName;
    private String objectName;
    private String roleName;
    private List<String> privileges;
    private Boolean grantOption;
    private Boolean direct;
    private Boolean revocable;
    private String rawStatement;
}
