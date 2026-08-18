package ai.chat2db.community.runtime.provider;

@FunctionalInterface
public interface ProviderApprovalHandler {
    ProviderApprovalDecision request(ProviderApprovalRequest request);
}
