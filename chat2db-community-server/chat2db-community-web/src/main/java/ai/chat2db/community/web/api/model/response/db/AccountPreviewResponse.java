package ai.chat2db.community.web.api.model.response.db;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.model.account.AccountDefinerImpact;
import lombok.Data;

import java.util.List;

@Data
public class AccountPreviewResponse {
    private AccountActionTypeEnum actionType;
    private String sql;
    private String previewToken;
    private String oldAccountSql;
    private String newAccountSql;
    private Boolean definerEnumerationComplete;
    private List<String> warningCodes;
    private List<AccountDefinerImpact> definerImpacts;
}
