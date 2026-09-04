package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

import java.util.List;

@Data
public class AccountGrantSummaryResponse {
    private Boolean readable;
    private String message;
    private List<String> rawStatements;
    private List<AccountGrantResponse> grants;
}
