package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

import java.util.List;

@Data
public class AccountResponse {
    private String user;
    private String host;
    private String displayName;
    private String authenticationPlugin;
    private Boolean locked;
    private Boolean role;
    private Boolean adminOption;
    private List<AccountRoleResponse> directRoles;
    private List<AccountRoleResponse> inheritedRoles;
    private List<AccountRoleResponse> effectiveRoles;
    private List<AccountRoleResponse> defaultRoles;
}
