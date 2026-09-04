package ai.chat2db.community.domain.api.model.account;

import lombok.Data;

import java.util.List;

@Data
public class AccountGrantSummary {
    private Boolean readable;
    private String message;
    private List<String> rawStatements;
    private List<AccountGrant> grants;
}
