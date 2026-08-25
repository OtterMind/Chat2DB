package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class AgentTaskServiceImpl implements IAgentTaskService {

    private final IAgentControlStorage storage;
    private final IAgentRuntimeControlStorage runtimeStorage;
    private final IAgentDefinitionService agentService;

    @Autowired
    public AgentTaskServiceImpl(IAgentControlStorage storage, IAgentRuntimeControlStorage runtimeStorage,
                                IAgentDefinitionService agentService) {
        this.storage = storage;
        this.runtimeStorage = runtimeStorage;
        this.agentService = agentService;
    }

    AgentTaskServiceImpl(IAgentControlStorage storage) {
        this.storage = storage;
        this.runtimeStorage = null;
        this.agentService = null;
    }

    AgentTaskServiceImpl(IAgentControlStorage storage, IAgentRuntimeControlStorage runtimeStorage) {
        this.storage = storage;
        this.runtimeStorage = runtimeStorage;
        this.agentService = null;
    }

    @Override
    public AgentTaskCreation create(AgentTaskCreateRequest request) {
        validate(request);
        AgentDefinition agent = getAgent(request.getAssigneeAgentId());
        if (agent == null) {
            throw new NoSuchElementException("agent not found: " + request.getAssigneeAgentId());
        }
        if (agent.getStatus() != AgentStatusEnum.ACTIVE) {
            throw new IllegalStateException("only active agents can receive tasks");
        }

        Date now = new Date();
        AgentTask task = new AgentTask();
        task.setId(UUID.randomUUID().toString());
        task.setTitle(request.getTitle().trim());
        task.setDescription(StringUtils.trimToNull(request.getDescription()));
        task.setAcceptanceCriteria(StringUtils.trimToNull(request.getAcceptanceCriteria()));
        task.setStatus(AgentTaskStatusEnum.TODO);
        task.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        task.setAssigneeAgentId(agent.getId());
        task.setCreatedBy(request.getCreatedBy());
        task.setOriginType(request.getOriginType() == null ? AgentTaskOriginTypeEnum.BOARD : request.getOriginType());
        task.setOriginSessionId(StringUtils.trimToNull(request.getOriginSessionId()));
        task.setOriginMessageId(StringUtils.trimToNull(request.getOriginMessageId()));
        task.setDataScopeSnapshot(AgentScopePolicy.requireAuthorizedScopes(
                request.getDataScopeSnapshot(), effectiveScopes(agent)));
        task.setGmtCreate(now);
        task.setGmtModified(now);
        task.setRevision(1L);

        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setTaskId(task.getId());
        run.setAgentId(agent.getId());
        run.setRuntimeType(agent.getRuntimeType());
        applyRuntimeProfile(run, agent);
        run.setTriggerType(AgentRunTriggerTypeEnum.TASK_CREATED);
        run.setStatus(AgentRunStatusEnum.QUEUED);
        run.setAttempt(1);
        run.setGmtCreate(now);
        run.setGmtModified(now);
        run.setRevision(1L);
        task.setCurrentRunId(run.getId());

        return storage.createTaskWithInitialRun(task, run);
    }

    @Override
    public AgentTask get(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("task id is required");
        }
        AgentTask task = storage.getTask(id);
        if (task == null) {
            throw new NoSuchElementException("task not found: " + id);
        }
        return task;
    }

    @Override
    public List<AgentTask> list() {
        return storage.listTasks();
    }

    @Override
    public List<AgentTask> listArchived() {
        return storage.listArchivedTasks();
    }

    @Override
    public List<AgentRun> listRuns(String taskId) {
        get(taskId);
        return storage.listRunsByTask(taskId);
    }

    @Override
    public AgentTask transition(AgentTaskTransitionRequest request) {
        if (request == null || StringUtils.isBlank(request.getTaskId())) {
            throw new IllegalArgumentException("task transition request and task id are required");
        }
        if (request.getExpectedRevision() == null || request.getExpectedRevision() <= 0) {
            throw new IllegalArgumentException("positive expected task revision is required");
        }
        if (request.getTargetStatus() == null) {
            throw new IllegalArgumentException("target task status is required");
        }
        AgentTask current = get(request.getTaskId());
        requireNotArchived(current);
        if (current.getRevision() == null
                || current.getRevision().longValue() != request.getExpectedRevision().longValue()) {
            throw new IllegalStateException("task revision has changed; refresh before retrying the transition");
        }
        AgentTaskStateMachine.requireTransition(current.getStatus(), request.getTargetStatus());

        AgentTask updated = copy(current);
        Date now = new Date();
        updated.setStatus(request.getTargetStatus());
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        if (request.getTargetStatus() == AgentTaskStatusEnum.DONE
                || request.getTargetStatus() == AgentTaskStatusEnum.CANCELLED) {
            updated.setCompletedAt(now);
        } else {
            updated.setCompletedAt(null);
        }
        return storage.updateTask(updated, request.getExpectedRevision());
    }

    @Override
    public AgentTask syncAssignedAgentScopes(String taskId, long expectedRevision) {
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("positive expected task revision is required");
        }
        AgentTask current = get(taskId);
        requireNotArchived(current);
        if (current.getRevision() == null || current.getRevision() != expectedRevision) {
            throw new IllegalStateException("task revision has changed; refresh before syncing data scopes");
        }
        boolean activeRun = listRuns(taskId).stream().anyMatch(run -> run.getStatus() == AgentRunStatusEnum.QUEUED
                || run.getStatus() == AgentRunStatusEnum.DISPATCHED
                || run.getStatus() == AgentRunStatusEnum.RUNNING
                || run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL);
        if (activeRun) {
            throw new IllegalStateException("task data scopes cannot change while a run is active");
        }
        AgentDefinition agent = getAgent(current.getAssigneeAgentId());
        if (agent == null || agent.getStatus() != AgentStatusEnum.ACTIVE) {
            throw new IllegalStateException("task agent is not active");
        }
        List<ai.chat2db.community.domain.api.model.agent.AgentDataScope> scopes =
                AgentScopePolicy.copyScopes(effectiveScopes(agent));
        if (scopes.isEmpty()) {
            throw new IllegalStateException("assigned agent has no database data scopes");
        }
        AgentTask updated = copy(current);
        Date now = new Date();
        updated.setDataScopeSnapshot(scopes);
        updated.setDataScopeSyncedAt(now);
        updated.setDataScopeSyncedFromAgentRevision(agent.getRevision());
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        return storage.updateTask(updated, expectedRevision);
    }

    private AgentDefinition getAgent(String id) {
        return agentService == null ? storage.getAgent(id) : agentService.get(id);
    }

    private List<ai.chat2db.community.domain.api.model.agent.AgentDataScope> effectiveScopes(AgentDefinition agent) {
        return agent.getEffectiveDataScopes() == null || agent.getEffectiveDataScopes().isEmpty()
                ? agent.getDataScopes() : agent.getEffectiveDataScopes();
    }

    @Override
    public AgentTaskCreation createRun(String taskId, AgentRunTriggerTypeEnum triggerType) {
        return createRun(taskId, triggerType, null);
    }

    @Override
    public AgentTaskCreation createRun(String taskId, AgentRunTriggerTypeEnum triggerType, String agentId) {
        AgentTask current = get(taskId);
        requireNotArchived(current);
        if (current.getStatus() == AgentTaskStatusEnum.CANCELLED) {
            throw new IllegalStateException("cancelled task cannot be continued");
        }
        AgentDefinition agent = storage.getAgent(StringUtils.defaultIfBlank(agentId, current.getAssigneeAgentId()));
        if (agent == null || agent.getStatus() != AgentStatusEnum.ACTIVE) {
            throw new IllegalStateException("task agent is not active");
        }
        List<AgentRun> previousRuns = listRuns(taskId);
        boolean activeRun = previousRuns.stream().anyMatch(run -> run.getStatus() == AgentRunStatusEnum.QUEUED
                || run.getStatus() == AgentRunStatusEnum.DISPATCHED
                || run.getStatus() == AgentRunStatusEnum.RUNNING
                || run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL);
        if (activeRun) {
            throw new IllegalStateException("task already has an active run");
        }

        Date now = new Date();
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setTaskId(current.getId());
        run.setAgentId(agent.getId());
        run.setRuntimeType(agent.getRuntimeType());
        applyRuntimeProfile(run, agent);
        run.setTriggerType(triggerType == null ? AgentRunTriggerTypeEnum.USER_MESSAGE : triggerType);
        run.setStatus(AgentRunStatusEnum.QUEUED);
        run.setAttempt(previousRuns.size() + 1);
        run.setParentRunId(current.getCurrentRunId());
        run.setGmtCreate(now);
        run.setGmtModified(now);
        run.setRevision(1L);

        AgentTask updated = copy(current);
        updated.setCurrentRunId(run.getId());
        updated.setStatus(AgentTaskStatusEnum.IN_PROGRESS);
        updated.setCompletedAt(null);
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        return storage.appendTaskRun(updated, run, current.getRevision());
    }

    @Override
    public AgentTaskCreation createConnectorRun(String taskId, String agentId) {
        AgentTask current = get(taskId);
        requireNotArchived(current);
        if (current.getOriginType() != AgentTaskOriginTypeEnum.CONNECTOR) {
            throw new IllegalStateException("concurrent audit Runs are limited to Connector Tasks");
        }
        AgentDefinition agent = getAgent(StringUtils.defaultIfBlank(agentId, current.getAssigneeAgentId()));
        if (agent == null || agent.getStatus() != AgentStatusEnum.ACTIVE) {
            throw new IllegalStateException("task agent is not active");
        }
        List<AgentRun> previousRuns = listRuns(taskId);
        Date now = new Date();
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setTaskId(current.getId());
        run.setAgentId(agent.getId());
        run.setRuntimeType(agent.getRuntimeType());
        applyRuntimeProfile(run, agent);
        run.setTriggerType(AgentRunTriggerTypeEnum.USER_MESSAGE);
        run.setStatus(AgentRunStatusEnum.QUEUED);
        run.setAttempt(previousRuns.size() + 1);
        run.setParentRunId(null);
        run.setGmtCreate(now);
        run.setGmtModified(now);
        run.setRevision(1L);

        AgentTask updated = copy(current);
        updated.setCurrentRunId(run.getId());
        updated.setStatus(AgentTaskStatusEnum.IN_PROGRESS);
        updated.setCompletedAt(null);
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        return storage.appendTaskRun(updated, run, current.getRevision());
    }

    private void applyRuntimeProfile(AgentRun run, AgentDefinition agent) {
        run.setRuntimeProfileId(StringUtils.trimToNull(agent.getRuntimeProfileId()));
        if (agent.getRuntimeType() != AgentRuntimeTypeEnum.EXTERNAL_AGENT) {
            run.setRuntimeProvider(null);
            run.setRuntimeProfileSnapshot(null);
            return;
        }
        if (runtimeStorage == null) {
            run.setRuntimeProfileSnapshot(agent.getRuntimeProfileId());
            return;
        }
        var profile = runtimeStorage.getRuntimeProfile(agent.getRuntimeProfileId());
        if (profile == null || !Boolean.TRUE.equals(profile.getEnabled())
                || profile.getTransport() != ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum.EXTERNAL_DAEMON
                || profile.getProvider() == ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum.SPRING_AI
                || !java.util.Objects.equals(profile.getCreatedBy(), agent.getCreatedBy())) {
            throw new IllegalStateException("external agent runtime profile is unavailable");
        }
        run.setRuntimeProvider(profile.getProvider());
        run.setRuntimeProfileSnapshot(JSON.toJSONString(profile));
    }

    private AgentTask copy(AgentTask source) {
        AgentTask copy = new AgentTask();
        copy.setId(source.getId());
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setAcceptanceCriteria(source.getAcceptanceCriteria());
        copy.setStatus(source.getStatus());
        copy.setPriority(source.getPriority());
        copy.setAssigneeAgentId(source.getAssigneeAgentId());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setOriginType(source.getOriginType());
        copy.setOriginSessionId(source.getOriginSessionId());
        copy.setOriginMessageId(source.getOriginMessageId());
        copy.setOriginScheduleId(source.getOriginScheduleId());
        copy.setOriginScheduleExecutionId(source.getOriginScheduleExecutionId());
        copy.setPlannedAt(source.getPlannedAt());
        copy.setDataScopeSnapshot(AgentScopePolicy.copyScopes(source.getDataScopeSnapshot()));
        copy.setDataScopeSyncedAt(source.getDataScopeSyncedAt());
        copy.setDataScopeSyncedFromAgentRevision(source.getDataScopeSyncedFromAgentRevision());
        copy.setCurrentRunId(source.getCurrentRunId());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setArchivedAt(source.getArchivedAt());
        copy.setRevision(source.getRevision());
        return copy;
    }

    @Override
    public AgentTask archive(String taskId, long expectedRevision) {
        AgentTask current = get(taskId);
        requireNotArchived(current);
        requireRevision(current, expectedRevision, "archiving");
        boolean activeRun = listRuns(taskId).stream().anyMatch(run -> run.getStatus() == AgentRunStatusEnum.QUEUED
                || run.getStatus() == AgentRunStatusEnum.DISPATCHED
                || run.getStatus() == AgentRunStatusEnum.RUNNING
                || run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL);
        if (activeRun) {
            throw new IllegalStateException("task cannot be archived while a run is active");
        }
        AgentTask updated = copy(current);
        Date now = new Date();
        updated.setArchivedAt(now);
        updated.setGmtModified(now);
        updated.setRevision(current.getRevision() + 1);
        return storage.updateTask(updated, expectedRevision);
    }

    @Override
    public void deleteArchived(String taskId, long expectedRevision) {
        AgentTask current = get(taskId);
        requireRevision(current, expectedRevision, "deleting");
        if (current.getArchivedAt() == null) {
            throw new IllegalStateException("only archived tasks can be permanently deleted");
        }
        storage.deleteTask(taskId, expectedRevision);
    }

    private void requireNotArchived(AgentTask task) {
        if (task.getArchivedAt() != null) {
            throw new IllegalStateException("archived task is read-only");
        }
    }

    private void requireRevision(AgentTask task, long expectedRevision, String action) {
        if (expectedRevision <= 0 || task.getRevision() == null || task.getRevision() != expectedRevision) {
            throw new IllegalStateException("task revision has changed; refresh before " + action);
        }
    }

    private void validate(AgentTaskCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("task request is required");
        }
        if (StringUtils.isBlank(request.getTitle())) {
            throw new IllegalArgumentException("task title is required");
        }
        if (request.getTitle().trim().length() > 256) {
            throw new IllegalArgumentException("task title must not exceed 256 characters");
        }
        if (StringUtils.isBlank(request.getAssigneeAgentId())) {
            throw new IllegalArgumentException("task assignee agent is required");
        }
        if (request.getPriority() != null && (request.getPriority() < -100 || request.getPriority() > 100)) {
            throw new IllegalArgumentException("task priority must be between -100 and 100");
        }
        if (request.getOriginType() == AgentTaskOriginTypeEnum.CHAT
                && StringUtils.isBlank(request.getOriginSessionId())) {
            throw new IllegalArgumentException("chat task origin session is required");
        }
    }
}
