package ai.chat2db.community.domain.api.model.account;

import lombok.Data;

@Data
public class AccountDefinerImpact {
    private String objectType;
    private String schemaName;
    private String objectName;
    private String definer;
}
