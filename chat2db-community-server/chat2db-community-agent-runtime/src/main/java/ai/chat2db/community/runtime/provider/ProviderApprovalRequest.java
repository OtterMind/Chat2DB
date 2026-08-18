package ai.chat2db.community.runtime.provider;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ProviderApprovalRequest {
    private String providerRequestId;
    private String toolCallId;
    private String title;
    private Map<String, Object> payload = new LinkedHashMap<>();
    private String allowOptionId;
    private String rejectOptionId;
}
