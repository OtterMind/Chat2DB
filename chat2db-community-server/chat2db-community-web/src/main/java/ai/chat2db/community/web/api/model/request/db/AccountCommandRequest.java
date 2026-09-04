package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AccountCommandRequest extends AccountRequest {
    private AccountActionTypeEnum actionType;
    private PrivilegeScopeEnum scope;
    private String databaseName;
    private String tableName;
    private String objectName;
    private List<String> privileges;
    private Boolean grantOption;
    private String password;
    private String previewToken;
}
