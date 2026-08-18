package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentRuntimeLeaseReconciliationJob {

    static final int DEFAULT_BATCH_SIZE = 100;

    private final IAgentRuntimeDispatchService dispatchService;

    public AgentRuntimeLeaseReconciliationJob(IAgentRuntimeDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Scheduled(initialDelayString = "${chat2db.agent.runtime.reconciliation-initial-delay-ms:15000}",
            fixedDelayString = "${chat2db.agent.runtime.reconciliation-delay-ms:15000}")
    public void reconcile() {
        try {
            int count = dispatchService.reconcileExpiredLeases(DEFAULT_BATCH_SIZE);
            if (count > 0) {
                log.info("Reconciled {} expired external agent runtime lease(s)", count);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to reconcile expired external agent runtime leases", exception);
        }
    }
}
