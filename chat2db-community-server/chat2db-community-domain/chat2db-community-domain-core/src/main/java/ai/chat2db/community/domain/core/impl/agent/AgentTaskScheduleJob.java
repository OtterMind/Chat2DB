package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.service.agent.IAgentTaskScheduleDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AgentTaskScheduleJob {

    static final int DEFAULT_BATCH_SIZE = 50;

    private final IAgentTaskScheduleDispatcher dispatcher;

    public AgentTaskScheduleJob(IAgentTaskScheduleDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelayString = "${chat2db.agent.schedule.initial-delay-ms:15000}",
            fixedDelayString = "${chat2db.agent.schedule.delay-ms:15000}")
    public void tick() {
        try {
            dispatcher.recover(DEFAULT_BATCH_SIZE);
            dispatcher.dispatchDue(DEFAULT_BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.warn("Failed to process agent task schedules", exception);
        }
    }
}
