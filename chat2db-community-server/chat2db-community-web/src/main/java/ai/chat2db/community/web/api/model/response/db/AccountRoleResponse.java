package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

@Data
public class AccountRoleResponse {

    private String user;

    private String host;

    private String displayName;

    private Boolean role;

    private Boolean adminOption;
}
