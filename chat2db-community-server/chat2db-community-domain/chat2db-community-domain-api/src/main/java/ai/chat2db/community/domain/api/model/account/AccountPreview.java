package ai.chat2db.community.domain.api.model.account;

import lombok.Data;

import java.util.List;

@Data
public class AccountPreview {
    private String actionType;
    private String sql;
    private String previewToken;
    private String oldAccountSql;
    private String newAccountSql;
    private Boolean definerEnumerationComplete;
    private List<String> warningCodes;
    private List<AccountDefinerImpact> definerImpacts;
}
