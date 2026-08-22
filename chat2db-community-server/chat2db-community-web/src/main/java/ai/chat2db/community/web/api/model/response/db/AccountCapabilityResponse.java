package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

import java.util.List;

@Data
public class AccountCapabilityResponse {
    private String dbType;
    private String productName;
    private String productVersion;
    private String currentUser;
    private String connectionUser;
    private Boolean accountListReadable;
    private Boolean accountLockSupported;
    private Boolean roleManagementSupported;
    private List<String> editablePrivileges;
    private String message;
}
