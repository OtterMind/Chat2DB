package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.service.agent.IAgentConnectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
public class AgentConnectorSessionReconciliationJob {
    static final int DEFAULT_BATCH_SIZE = 100;

    private final IAgentConnectorService connectorService;
    private final long idleTimeoutMs;

    public AgentConnectorSessionReconciliationJob(IAgentConnectorService connectorService,
            @Value("${chat2db.agent.connector.idle-timeout-ms:3600000}") long idleTimeoutMs) {
        this.connectorService = connectorService;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    @Scheduled(initialDelayString = "${chat2db.agent.connector.reconciliation-initial-delay-ms:15000}",
            fixedDelayString = "${chat2db.agent.connector.reconciliation-delay-ms:60000}")
    public void reconcile() {
        try {
            int count = connectorService.reconcileSessions(new Date(System.currentTimeMillis() - idleTimeoutMs),
                    DEFAULT_BATCH_SIZE);
            if (count > 0) log.info("Expired {} idle Agent Connector Session(s)", count);
        } catch (RuntimeException exception) {
            log.warn("Failed to reconcile Agent Connector Sessions", exception);
        }
    }
}
