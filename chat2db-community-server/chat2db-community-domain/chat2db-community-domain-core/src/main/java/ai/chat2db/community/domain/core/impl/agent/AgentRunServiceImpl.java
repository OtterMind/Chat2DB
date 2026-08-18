package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.NoSuchElementException;

@Service
public class AgentRunServiceImpl implements IAgentRunService {

    private final IAgentControlStorage storage;

    public AgentRunServiceImpl(IAgentControlStorage storage) {
        this.storage = storage;
    }

    @Override
    public AgentRun get(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("run id is required");
        }
        AgentRun run = storage.getRun(id);
        if (run == null) {
            throw new NoSuchElementException("run not found: " + id);
        }
        return run;
    }

    @Override
    public AgentRun transition(AgentRunTransitionRequest request) {
        validate(request);
        AgentRun current = get(request.getRunId());
        if (current.getRevision() == null
                || current.getRevision().longValue() != request.getExpectedRevision().longValue()) {
            throw new IllegalStateException("run revision has changed; refresh before retrying the transition");
        }
        AgentRunStateMachine.requireTransition(current.getStatus(), request.getTargetStatus());

        Date now = new Date();
        AgentRun updated = copy(current);
        updated.setStatus(request.getTargetStatus());
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        if (request.getTargetStatus() == AgentRunStatusEnum.RUNNING && updated.getStartedAt() == null) {
            updated.setStartedAt(now);
        }
        if (AgentRunStateMachine.terminal(request.getTargetStatus())) {
            updated.setCompletedAt(now);
        }
        updated.setFailureReason(StringUtils.trimToNull(request.getFailureReason()));
        updated.setResultSummary(StringUtils.trimToNull(request.getResultSummary()));
        return storage.updateRun(updated, request.getExpectedRevision());
    }

    private void validate(AgentRunTransitionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("run transition request is required");
        }
        if (StringUtils.isBlank(request.getRunId())) {
            throw new IllegalArgumentException("run id is required");
        }
        if (request.getExpectedRevision() == null || request.getExpectedRevision() <= 0) {
            throw new IllegalArgumentException("positive expected run revision is required");
        }
        if (request.getTargetStatus() == null) {
            throw new IllegalArgumentException("target run status is required");
        }
        if (request.getTargetStatus() == AgentRunStatusEnum.FAILED
                && StringUtils.isBlank(request.getFailureReason())) {
            throw new IllegalArgumentException("failed run requires a failure reason");
        }
    }

    private AgentRun copy(AgentRun source) {
        AgentRun copy = new AgentRun();
        copy.setId(source.getId());
        copy.setTaskId(source.getTaskId());
        copy.setAgentId(source.getAgentId());
        copy.setRuntimeType(source.getRuntimeType());
        copy.setRuntimeProfileId(source.getRuntimeProfileId());
        copy.setRuntimeProvider(source.getRuntimeProvider());
        copy.setRuntimeProfileSnapshot(source.getRuntimeProfileSnapshot());
        copy.setProviderSessionId(source.getProviderSessionId());
        copy.setTriggerType(source.getTriggerType());
        copy.setStatus(source.getStatus());
        copy.setAttempt(source.getAttempt());
        copy.setParentRunId(source.getParentRunId());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        copy.setStartedAt(source.getStartedAt());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setFailureReason(source.getFailureReason());
        copy.setResultSummary(source.getResultSummary());
        copy.setRevision(source.getRevision());
        return copy;
    }
}
