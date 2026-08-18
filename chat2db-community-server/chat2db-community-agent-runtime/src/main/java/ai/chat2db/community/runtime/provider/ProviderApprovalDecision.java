package ai.chat2db.community.runtime.provider;

import lombok.Data;

@Data
public class ProviderApprovalDecision {
    private boolean approved;
    private String selectedOptionId;
    private String reason;
}
