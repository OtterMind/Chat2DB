package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleCatchUpPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleConcurrencyPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionSourceEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleReasonCodeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleClaim;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleUpdateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRunCoordinator;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskScheduleDispatcher;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskScheduleService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentTaskScheduleStorage;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class AgentTaskScheduleServiceImpl implements IAgentTaskScheduleService, IAgentTaskScheduleDispatcher {

    static final long MAX_LATENESS_MS = 5 * 60 * 1000L;
    static final long LEASE_DURATION_MS = 2 * 60 * 1000L;

    private final IAgentTaskScheduleStorage scheduleStorage;
    private final IAgentControlStorage agentStorage;
    private final IAgentRuntimeControlStorage runtimeStorage;
    private final IAgentRuntimeControlService runtimeControlService;
    private final IAgentRunService runService;
    private final IAgentRunCoordinator runCoordinator;
    private IAgentDefinitionService agentService;
    private final Clock clock;

    @Autowired
    public AgentTaskScheduleServiceImpl(IAgentTaskScheduleStorage scheduleStorage,
                                        IAgentControlStorage agentStorage,
                                        IAgentRuntimeControlStorage runtimeStorage,
                                        IAgentRuntimeControlService runtimeControlService,
                                        IAgentRunService runService,
                                        IAgentRunCoordinator runCoordinator,
                                        IAgentDefinitionService agentService) {
        this(scheduleStorage, agentStorage, runtimeStorage, runtimeControlService,
                runService, runCoordinator, Clock.systemUTC());
        this.agentService = agentService;
    }

    AgentTaskScheduleServiceImpl(IAgentTaskScheduleStorage scheduleStorage,
                                 IAgentControlStorage agentStorage,
                                 IAgentRuntimeControlStorage runtimeStorage,
                                 IAgentRuntimeControlService runtimeControlService,
                                 IAgentRunService runService,
                                 IAgentRunCoordinator runCoordinator,
                                 Clock clock) {
        this.scheduleStorage = scheduleStorage;
        this.agentStorage = agentStorage;
        this.runtimeStorage = runtimeStorage;
        this.runtimeControlService = runtimeControlService;
        this.runService = runService;
        this.runCoordinator = runCoordinator;
        this.clock = clock;
        this.agentService = null;
    }

    @Override
    public AgentTaskSchedule create(AgentTaskScheduleCreateRequest request) {
        Date now = now();
        validateTemplate(request == null ? null : request.getName(),
                request == null ? null : request.getTaskTitle(),
                request == null ? null : request.getAssigneeAgentId(),
                request == null ? null : request.getPriority(),
                request == null ? null : request.getCreatedBy());
        AgentDefinition agent = requireAgent(request.getAssigneeAgentId(), request.getCreatedBy());
        AgentTaskSchedule schedule = new AgentTaskSchedule();
        schedule.setId(UUID.randomUUID().toString());
        applyTemplate(schedule, request.getName(), request.getTaskTitle(), request.getTaskDescription(),
                request.getAcceptanceCriteria(), request.getAssigneeAgentId(), request.getPriority(),
                AgentScopePolicy.requireAuthorizedScopes(request.getDataScopeSnapshot(), effectiveScopes(agent)),
                request.getScheduleType(), request.getScheduledAt(), request.getCronExpression(),
                request.getTimezone(), now);
        schedule.setStatus(AgentTaskScheduleStatusEnum.ACTIVE);
        schedule.setConcurrencyPolicy(AgentTaskScheduleConcurrencyPolicyEnum.SKIP);
        schedule.setCatchUpPolicy(AgentTaskScheduleCatchUpPolicyEnum.LATEST_ONLY);
        schedule.setCreatedBy(request.getCreatedBy());
        schedule.setGmtCreate(now);
        schedule.setGmtModified(now);
        schedule.setRevision(1L);
        return scheduleStorage.createSchedule(schedule);
    }

    @Override
    public AgentTaskSchedule update(AgentTaskScheduleUpdateRequest request) {
        if (request == null || StringUtils.isBlank(request.getScheduleId())
                || request.getExpectedRevision() == null || request.getExpectedRevision() <= 0) {
            throw new IllegalArgumentException("schedule id and positive expected revision are required");
        }
        AgentTaskSchedule current = get(request.getScheduleId());
        if (current.getStatus() == AgentTaskScheduleStatusEnum.ARCHIVED) {
            throw new IllegalStateException("archived schedule is read-only");
        }
        requireRevision(current, request.getExpectedRevision());
        validateTemplate(request.getName(), request.getTaskTitle(), request.getAssigneeAgentId(),
                request.getPriority(), current.getCreatedBy());
        AgentDefinition agent = requireAgent(request.getAssigneeAgentId(), current.getCreatedBy());
        AgentTaskSchedule updated = copy(current);
        applyTemplate(updated, request.getName(), request.getTaskTitle(), request.getTaskDescription(),
                request.getAcceptanceCriteria(), request.getAssigneeAgentId(), request.getPriority(),
                AgentScopePolicy.requireAuthorizedScopes(request.getDataScopeSnapshot(), effectiveScopes(agent)),
                request.getScheduleType(), request.getScheduledAt(), request.getCronExpression(),
                request.getTimezone(), now());
        if (current.getStatus() != AgentTaskScheduleStatusEnum.ACTIVE) {
            updated.setNextRunAt(null);
        }
        updated.setGmtModified(now());
        updated.setRevision(current.getRevision() + 1);
        return scheduleStorage.updateSchedule(updated, current.getRevision());
    }

    @Override
    public AgentTaskSchedule get(String id) {
        if (StringUtils.isBlank(id)) throw new IllegalArgumentException("schedule id is required");
        AgentTaskSchedule schedule = scheduleStorage.getSchedule(id);
        if (schedule == null) throw new NoSuchElementException("agent task schedule not found: " + id);
        return schedule;
    }

    @Override
    public List<AgentTaskSchedule> list(Long createdBy) {
        if (createdBy == null) throw new IllegalArgumentException("schedule owner is required");
        return scheduleStorage.listSchedules(createdBy);
    }

    @Override
    public List<AgentTaskScheduleExecution> listExecutions(String scheduleId) {
        get(scheduleId);
        return scheduleStorage.listExecutions(scheduleId);
    }

    @Override
    public AgentTaskSchedule changeStatus(String scheduleId, long expectedRevision,
                                          AgentTaskScheduleStatusEnum status) {
        AgentTaskSchedule current = get(scheduleId);
        requireRevision(current, expectedRevision);
        if (status == null || status == current.getStatus()) return current;
        if (current.getStatus() == AgentTaskScheduleStatusEnum.ARCHIVED) {
            throw new IllegalStateException("archived schedule is read-only");
        }
        if (status != AgentTaskScheduleStatusEnum.ACTIVE
                && status != AgentTaskScheduleStatusEnum.PAUSED
                && status != AgentTaskScheduleStatusEnum.ARCHIVED) {
            throw new IllegalArgumentException("unsupported schedule status: " + status);
        }
        AgentTaskSchedule updated = copy(current);
        updated.setStatus(status);
        updated.setNextRunAt(status == AgentTaskScheduleStatusEnum.ACTIVE
                ? nextForActivation(updated, now()) : null);
        updated.setGmtModified(now());
        updated.setRevision(current.getRevision() + 1);
        return scheduleStorage.updateSchedule(updated, current.getRevision());
    }

    @Override
    public AgentTaskScheduleExecution runNow(String scheduleId) {
        AgentTaskSchedule schedule = get(scheduleId);
        if (schedule.getStatus() == AgentTaskScheduleStatusEnum.ARCHIVED) {
            throw new IllegalStateException("archived schedule cannot run");
        }
        Date plannedAt = now();
        AgentTaskScheduleClaim claim = claim(schedule, plannedAt, AgentTaskScheduleExecutionSourceEnum.MANUAL);
        return claim.isClaimed() ? executeClaimed(schedule, claim.getExecution(), false) : claim.getExecution();
    }

    @Override
    public List<Date> preview(String cronExpression, String timezone, int count) {
        if (count < 1 || count > 10) throw new IllegalArgumentException("preview count must be between 1 and 10");
        return AgentTaskScheduleCron.next(cronExpression, timezone, now(), count);
    }

    @Override
    public int dispatchDue(int limit) {
        Date now = now();
        int dispatched = 0;
        for (AgentTaskSchedule schedule : scheduleStorage.listDueSchedules(now, boundedLimit(limit))) {
            try {
                Date plannedAt = latestDueOccurrence(schedule, schedule.getNextRunAt(), now);
                AgentTaskScheduleClaim claim = claim(
                        schedule, plannedAt, AgentTaskScheduleExecutionSourceEnum.SCHEDULE);
                if (claim.isClaimed()) {
                    executeClaimed(schedule, claim.getExecution(), true);
                    dispatched++;
                } else if (claim.getExecution() != null) {
                    advancePastExisting(schedule, plannedAt);
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to dispatch agent task schedule {}", schedule.getId(), exception);
            }
        }
        return dispatched;
    }

    @Override
    public int recover(int limit) {
        int recovered = 0;
        Date now = now();
        for (AgentTaskScheduleExecution execution
                : scheduleStorage.listRecoverableExecutions(now, boundedLimit(limit))) {
            try {
                if (execution.getStatus() == AgentTaskScheduleExecutionStatusEnum.TASK_CREATED) {
                    AgentRun run = runService.get(execution.getRunId());
                    if (run.getStatus() == AgentRunStatusEnum.QUEUED) runCoordinator.dispatch(run.getId());
                    finishDispatched(scheduleStorage.getExecution(execution.getId()));
                    recovered++;
                    continue;
                }
                AgentTaskSchedule schedule = get(execution.getScheduleId());
                AgentTaskScheduleExecution replacement = newExecution(
                        schedule, execution.getPlannedAt(), execution.getSource());
                AgentTaskScheduleClaim claim = scheduleStorage.claimExecution(replacement, now);
                if (claim.isClaimed()) {
                    executeClaimed(schedule, claim.getExecution(), execution.getSource()
                            == AgentTaskScheduleExecutionSourceEnum.SCHEDULE);
                    recovered++;
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to recover agent task schedule execution {}", execution.getId(), exception);
            }
        }
        return recovered;
    }

    private AgentTaskScheduleExecution executeClaimed(AgentTaskSchedule schedule,
                                                      AgentTaskScheduleExecution execution,
                                                      boolean enforceLateness) {
        Date now = now();
        Date nextRunAt = execution.getSource() == AgentTaskScheduleExecutionSourceEnum.MANUAL
                ? schedule.getNextRunAt() : nextAfter(schedule, execution.getPlannedAt());
        AgentTaskSchedule scheduleForCommit = copy(schedule);
        if (execution.getSource() == AgentTaskScheduleExecutionSourceEnum.SCHEDULE
                && schedule.getScheduleType() == AgentTaskScheduleTypeEnum.ONCE) {
            scheduleForCommit.setStatus(AgentTaskScheduleStatusEnum.PAUSED);
        }
        if (enforceLateness && now.getTime() - execution.getPlannedAt().getTime() > MAX_LATENESS_MS) {
            Date futureRunAt = schedule.getScheduleType() == AgentTaskScheduleTypeEnum.CRON
                    ? nextAfter(schedule, now) : null;
            return finishWithoutTask(scheduleForCommit, execution, futureRunAt,
                    AgentTaskScheduleReasonCodeEnum.MISSED_WINDOW, "scheduled occurrence exceeded lateness window");
        }
        if (scheduleStorage.hasActiveExecutionTask(schedule.getId())) {
            return finishWithoutTask(scheduleForCommit, execution, nextRunAt,
                    AgentTaskScheduleReasonCodeEnum.PREVIOUS_EXECUTION_ACTIVE,
                    "a previous task from this schedule still has an active run");
        }
        AgentDefinition agent;
        try {
            agent = requireAgent(schedule.getAssigneeAgentId(), schedule.getCreatedBy());
        } catch (RuntimeException exception) {
            return finishWithoutTask(scheduleForCommit, execution, nextRunAt,
                    AgentTaskScheduleReasonCodeEnum.AGENT_UNAVAILABLE, exception.getMessage());
        }
        List<ai.chat2db.community.domain.api.model.agent.AgentDataScope> scopes;
        try {
            scopes = AgentScopePolicy.requireAuthorizedScopes(
                    schedule.getDataScopeSnapshot(), effectiveScopes(agent));
        } catch (RuntimeException exception) {
            return finishWithoutTask(scheduleForCommit, execution, nextRunAt,
                    AgentTaskScheduleReasonCodeEnum.DATA_SCOPE_REVOKED, exception.getMessage());
        }
        AgentRuntimeProfile profile = null;
        if (agent.getRuntimeType() == AgentRuntimeTypeEnum.EXTERNAL_AGENT) {
            profile = runtimeStorage.getRuntimeProfile(agent.getRuntimeProfileId());
            if (!validExternalProfile(profile, agent)) {
                return finishWithoutTask(scheduleForCommit, execution, nextRunAt,
                        AgentTaskScheduleReasonCodeEnum.RUNTIME_PROFILE_UNAVAILABLE,
                        "external runtime profile is unavailable");
            }
            if (!runtimeAvailable(profile.getProvider())) {
                return finishWithoutTask(scheduleForCommit, execution, nextRunAt,
                        AgentTaskScheduleReasonCodeEnum.RUNTIME_OFFLINE,
                        "no online runtime instance has available capacity");
            }
        }

        AgentTask task = task(schedule, execution, agent, scopes, now);
        AgentRun run = run(task, agent, profile, now);
        AgentTaskCreation creation = scheduleStorage.createScheduledTask(
                scheduleForCommit, execution, task, run, nextRunAt,
                schedule.getRevision(), execution.getRevision(), execution.getLeaseToken());
        try {
            runCoordinator.dispatch(creation.getInitialRun().getId());
            return finishDispatched(scheduleStorage.getExecution(execution.getId()));
        } catch (RuntimeException exception) {
            AgentTaskScheduleExecution failed = scheduleStorage.getExecution(execution.getId());
            long revision = failed.getRevision();
            failed.setStatus(AgentTaskScheduleExecutionStatusEnum.FAILED);
            failed.setReasonCode(AgentTaskScheduleReasonCodeEnum.DISPATCH_FAILED);
            failed.setFailureReason(StringUtils.defaultIfBlank(exception.getMessage(), "run dispatch failed"));
            failed.setGmtModified(now());
            failed.setRevision(revision + 1);
            return scheduleStorage.updateExecution(failed, revision, failed.getLeaseToken());
        }
    }

    private AgentTaskScheduleExecution finishWithoutTask(AgentTaskSchedule schedule,
                                                         AgentTaskScheduleExecution execution,
                                                         Date nextRunAt,
                                                         AgentTaskScheduleReasonCodeEnum reason,
                                                         String failureReason) {
        AgentTaskScheduleExecution terminal = copy(execution);
        terminal.setStatus(AgentTaskScheduleExecutionStatusEnum.SKIPPED);
        terminal.setReasonCode(reason);
        terminal.setFailureReason(failureReason);
        terminal.setGmtModified(now());
        terminal.setRevision(execution.getRevision() + 1);
        return scheduleStorage.finishExecutionWithoutTask(schedule, terminal, nextRunAt,
                schedule.getRevision(), execution.getRevision(), execution.getLeaseToken());
    }

    private AgentTaskScheduleExecution finishDispatched(AgentTaskScheduleExecution execution) {
        if (execution.getStatus() == AgentTaskScheduleExecutionStatusEnum.DISPATCHED) return execution;
        long revision = execution.getRevision();
        execution.setStatus(AgentTaskScheduleExecutionStatusEnum.DISPATCHED);
        execution.setGmtModified(now());
        execution.setRevision(revision + 1);
        return scheduleStorage.updateExecution(execution, revision, execution.getLeaseToken());
    }

    private AgentTaskScheduleClaim claim(AgentTaskSchedule schedule, Date plannedAt,
                                         AgentTaskScheduleExecutionSourceEnum source) {
        return scheduleStorage.claimExecution(newExecution(schedule, plannedAt, source), now());
    }

    private AgentTaskScheduleExecution newExecution(AgentTaskSchedule schedule, Date plannedAt,
                                                    AgentTaskScheduleExecutionSourceEnum source) {
        Date now = now();
        AgentTaskScheduleExecution execution = new AgentTaskScheduleExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setScheduleId(schedule.getId());
        execution.setSource(source);
        execution.setPlannedAt(plannedAt);
        execution.setStatus(AgentTaskScheduleExecutionStatusEnum.CLAIMED);
        execution.setAttempt(1);
        execution.setLeaseToken(UUID.randomUUID().toString());
        execution.setLeaseExpiresAt(new Date(now.getTime() + LEASE_DURATION_MS));
        execution.setGmtCreate(now);
        execution.setGmtModified(now);
        execution.setRevision(1L);
        return execution;
    }

    private void advancePastExisting(AgentTaskSchedule schedule, Date plannedAt) {
        AgentTaskSchedule latest = get(schedule.getId());
        if (latest.getNextRunAt() == null || latest.getNextRunAt().after(plannedAt)) return;
        AgentTaskSchedule updated = copy(latest);
        updated.setNextRunAt(nextAfter(updated, plannedAt));
        if (updated.getScheduleType() == AgentTaskScheduleTypeEnum.ONCE) {
            updated.setStatus(AgentTaskScheduleStatusEnum.PAUSED);
        }
        updated.setLastRunAt(plannedAt);
        updated.setGmtModified(now());
        updated.setRevision(latest.getRevision() + 1);
        try {
            scheduleStorage.updateSchedule(updated, latest.getRevision());
        } catch (ConcurrentModificationException ignored) {
            // Another scanner advanced the same schedule.
        }
    }

    private AgentTask task(AgentTaskSchedule schedule, AgentTaskScheduleExecution execution,
                           AgentDefinition agent,
                           List<ai.chat2db.community.domain.api.model.agent.AgentDataScope> scopes,
                           Date now) {
        AgentTask task = new AgentTask();
        task.setId(UUID.randomUUID().toString());
        task.setTitle(schedule.getTaskTitle());
        task.setDescription(schedule.getTaskDescription());
        task.setAcceptanceCriteria(schedule.getAcceptanceCriteria());
        task.setStatus(AgentTaskStatusEnum.TODO);
        task.setPriority(schedule.getPriority());
        task.setAssigneeAgentId(agent.getId());
        task.setCreatedBy(schedule.getCreatedBy());
        task.setOriginType(AgentTaskOriginTypeEnum.SCHEDULE);
        task.setOriginScheduleId(schedule.getId());
        task.setOriginScheduleExecutionId(execution.getId());
        task.setPlannedAt(execution.getPlannedAt());
        task.setDataScopeSnapshot(scopes);
        task.setGmtCreate(now);
        task.setGmtModified(now);
        task.setRevision(1L);
        return task;
    }

    private AgentRun run(AgentTask task, AgentDefinition agent, AgentRuntimeProfile profile, Date now) {
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setTaskId(task.getId());
        run.setAgentId(agent.getId());
        run.setRuntimeType(agent.getRuntimeType());
        run.setRuntimeProfileId(StringUtils.trimToNull(agent.getRuntimeProfileId()));
        if (profile != null) {
            run.setRuntimeProvider(profile.getProvider());
            run.setRuntimeProfileSnapshot(JSON.toJSONString(profile));
        }
        run.setTriggerType(AgentRunTriggerTypeEnum.SCHEDULED);
        run.setStatus(AgentRunStatusEnum.QUEUED);
        run.setAttempt(1);
        run.setGmtCreate(now);
        run.setGmtModified(now);
        run.setRevision(1L);
        task.setCurrentRunId(run.getId());
        return run;
    }

    private boolean validExternalProfile(AgentRuntimeProfile profile, AgentDefinition agent) {
        return profile != null && Boolean.TRUE.equals(profile.getEnabled())
                && profile.getTransport() == AgentRuntimeTransportEnum.EXTERNAL_DAEMON
                && profile.getProvider() != AgentRuntimeProviderEnum.SPRING_AI
                && Objects.equals(profile.getCreatedBy(), agent.getCreatedBy());
    }

    private boolean runtimeAvailable(AgentRuntimeProviderEnum provider) {
        return runtimeControlService.listInstances().stream()
                .filter(instance -> instance.getProvider() == provider)
                .filter(this::online)
                .anyMatch(instance -> instance.getActiveRuns() < instance.getMaxConcurrency());
    }

    private boolean online(AgentRuntimeInstance instance) {
        return instance.getStatus() == AgentRuntimeInstanceStatusEnum.ONLINE
                || instance.getStatus() == AgentRuntimeInstanceStatusEnum.DEGRADED;
    }

    private AgentDefinition requireAgent(String id, Long ownerId) {
        AgentDefinition agent = agentService == null ? agentStorage.getAgent(id) : agentService.get(id);
        if (agent == null || agent.getStatus() != AgentStatusEnum.ACTIVE) {
            throw new IllegalStateException("scheduled task agent is unavailable");
        }
        if (agent.getCreatedBy() != null && !Objects.equals(agent.getCreatedBy(), ownerId)) {
            throw new IllegalArgumentException("scheduled task agent is not accessible to its owner");
        }
        return agent;
    }

    private List<ai.chat2db.community.domain.api.model.agent.AgentDataScope> effectiveScopes(AgentDefinition agent) {
        return agent.getEffectiveDataScopes() == null || agent.getEffectiveDataScopes().isEmpty()
                ? agent.getDataScopes() : agent.getEffectiveDataScopes();
    }

    private void validateTemplate(String name, String title, String agentId,
                                  Integer priority, Long ownerId) {
        if (StringUtils.isBlank(name) || name.trim().length() > 128) {
            throw new IllegalArgumentException("schedule name is required and must not exceed 128 characters");
        }
        if (StringUtils.isBlank(title) || title.trim().length() > 256) {
            throw new IllegalArgumentException("task title is required and must not exceed 256 characters");
        }
        if (StringUtils.isBlank(agentId)) throw new IllegalArgumentException("schedule agent is required");
        if (ownerId == null) throw new IllegalArgumentException("schedule owner is required");
        if (priority != null && (priority < -100 || priority > 100)) {
            throw new IllegalArgumentException("schedule priority must be between -100 and 100");
        }
    }

    private void applyTemplate(AgentTaskSchedule schedule, String name, String title,
                               String description, String acceptanceCriteria, String agentId,
                               Integer priority,
                               List<ai.chat2db.community.domain.api.model.agent.AgentDataScope> scopes,
                               AgentTaskScheduleTypeEnum type, Date scheduledAt,
                               String cronExpression, String timezone, Date now) {
        if (type == null) throw new IllegalArgumentException("schedule type is required");
        String resolvedTimezone = StringUtils.defaultIfBlank(timezone, "UTC").trim();
        schedule.setName(name.trim());
        schedule.setTaskTitle(title.trim());
        schedule.setTaskDescription(StringUtils.trimToNull(description));
        schedule.setAcceptanceCriteria(StringUtils.trimToNull(acceptanceCriteria));
        schedule.setAssigneeAgentId(agentId.trim());
        schedule.setPriority(priority == null ? 0 : priority);
        schedule.setDataScopeSnapshot(AgentScopePolicy.copyScopes(scopes));
        schedule.setScheduleType(type);
        schedule.setTimezone(resolvedTimezone);
        if (type == AgentTaskScheduleTypeEnum.ONCE) {
            if (scheduledAt == null || !scheduledAt.after(now)) {
                throw new IllegalArgumentException("one-time schedule must be in the future");
            }
            if (StringUtils.isNotBlank(cronExpression)) {
                throw new IllegalArgumentException("one-time schedule must not include a cron expression");
            }
            // Validates the timezone even though ONCE is persisted as an absolute instant.
            AgentTaskScheduleCron.next("0 0 1 1 *", resolvedTimezone, now, 1);
            schedule.setScheduledAt(new Date(scheduledAt.getTime()));
            schedule.setCronExpression(null);
            schedule.setNextRunAt(new Date(scheduledAt.getTime()));
        } else {
            if (scheduledAt != null) throw new IllegalArgumentException("cron schedule must not include scheduledAt");
            List<Date> next = AgentTaskScheduleCron.next(cronExpression, resolvedTimezone, now, 1);
            if (next.isEmpty()) throw new IllegalArgumentException("cron schedule has no future occurrence");
            schedule.setScheduledAt(null);
            schedule.setCronExpression(cronExpression.trim().replaceAll("\\s+", " "));
            schedule.setNextRunAt(next.get(0));
        }
    }

    private Date nextAfter(AgentTaskSchedule schedule, Date after) {
        if (schedule.getScheduleType() == AgentTaskScheduleTypeEnum.ONCE) return null;
        List<Date> next = AgentTaskScheduleCron.next(
                schedule.getCronExpression(), schedule.getTimezone(), after, 1);
        return next.isEmpty() ? null : next.get(0);
    }

    private Date nextForActivation(AgentTaskSchedule schedule, Date now) {
        if (schedule.getScheduleType() == AgentTaskScheduleTypeEnum.ONCE) {
            if (schedule.getScheduledAt() == null || !schedule.getScheduledAt().after(now)) {
                throw new IllegalStateException("one-time schedule is no longer runnable");
            }
            return new Date(schedule.getScheduledAt().getTime());
        }
        return nextAfter(schedule, now);
    }

    /**
     * Implements LATEST_ONLY without replaying every occurrence after a long outage. When the
     * stored occurrence is already outside the lateness window it is retained only to produce one
     * auditable MISSED_WINDOW record; completion advances directly from the current time.
     */
    private Date latestDueOccurrence(AgentTaskSchedule schedule, Date firstDue, Date now) {
        if (schedule.getScheduleType() == AgentTaskScheduleTypeEnum.ONCE
                || now.getTime() - firstDue.getTime() > MAX_LATENESS_MS) {
            return firstDue;
        }
        Date latest = firstDue;
        Date candidate = nextAfter(schedule, latest);
        while (candidate != null && !candidate.after(now)) {
            latest = candidate;
            candidate = nextAfter(schedule, latest);
        }
        return latest;
    }

    private AgentTaskSchedule copy(AgentTaskSchedule source) {
        AgentTaskSchedule copy = new AgentTaskSchedule();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setTaskTitle(source.getTaskTitle());
        copy.setTaskDescription(source.getTaskDescription());
        copy.setAcceptanceCriteria(source.getAcceptanceCriteria());
        copy.setAssigneeAgentId(source.getAssigneeAgentId());
        copy.setPriority(source.getPriority());
        copy.setDataScopeSnapshot(AgentScopePolicy.copyScopes(source.getDataScopeSnapshot()));
        copy.setScheduleType(source.getScheduleType());
        copy.setScheduledAt(source.getScheduledAt());
        copy.setCronExpression(source.getCronExpression());
        copy.setTimezone(source.getTimezone());
        copy.setStatus(source.getStatus());
        copy.setConcurrencyPolicy(source.getConcurrencyPolicy());
        copy.setCatchUpPolicy(source.getCatchUpPolicy());
        copy.setNextRunAt(source.getNextRunAt());
        copy.setLastRunAt(source.getLastRunAt());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private AgentTaskScheduleExecution copy(AgentTaskScheduleExecution source) {
        AgentTaskScheduleExecution copy = new AgentTaskScheduleExecution();
        copy.setId(source.getId());
        copy.setScheduleId(source.getScheduleId());
        copy.setSource(source.getSource());
        copy.setPlannedAt(source.getPlannedAt());
        copy.setStatus(source.getStatus());
        copy.setTaskId(source.getTaskId());
        copy.setRunId(source.getRunId());
        copy.setAttempt(source.getAttempt());
        copy.setLeaseToken(source.getLeaseToken());
        copy.setLeaseExpiresAt(source.getLeaseExpiresAt());
        copy.setReasonCode(source.getReasonCode());
        copy.setFailureReason(source.getFailureReason());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        copy.setRevision(source.getRevision());
        return copy;
    }

    private void requireRevision(AgentTaskSchedule schedule, long revision) {
        if (revision <= 0 || schedule.getRevision() == null || schedule.getRevision() != revision) {
            throw new ConcurrentModificationException("schedule revision has changed: " + schedule.getId());
        }
    }

    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private Date now() {
        return Date.from(clock.instant());
    }
}
