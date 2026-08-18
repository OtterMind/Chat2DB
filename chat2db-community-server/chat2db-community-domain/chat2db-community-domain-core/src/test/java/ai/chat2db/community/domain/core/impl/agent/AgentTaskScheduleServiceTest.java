package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleCatchUpPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleConcurrencyPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionSourceEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleReasonCodeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleClaim;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleCreateRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRunCoordinator;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentTaskScheduleStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTaskScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T10:04:30Z");

    private MemoryScheduleStorage storage;
    private AgentTaskScheduleServiceImpl service;
    private AgentDefinition agent;
    private int dispatchCount;

    @BeforeEach
    void setUp() {
        storage = new MemoryScheduleStorage();
        agent = new AgentDefinition();
        agent.setId("agent-1");
        agent.setName("Analysis Agent");
        agent.setStatus(AgentStatusEnum.ACTIVE);
        agent.setRuntimeType(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI);
        agent.setDataScopes(List.of());
        agent.setCreatedBy(7L);

        IAgentControlStorage agentStorage = proxy(IAgentControlStorage.class, (method, args) ->
                method.equals("getAgent") ? agent : defaultValue(method));
        IAgentRuntimeControlStorage runtimeStorage = proxy(IAgentRuntimeControlStorage.class,
                (method, args) -> defaultValue(method));
        IAgentRuntimeControlService runtimeService = proxy(IAgentRuntimeControlService.class,
                (method, args) -> method.equals("listInstances") ? List.of() : defaultValue(method));
        IAgentRunService runService = proxy(IAgentRunService.class, (method, args) ->
                method.equals("get") ? storage.runs.get(args[0]) : defaultValue(method));
        IAgentRunCoordinator coordinator = proxy(IAgentRunCoordinator.class, (method, args) -> {
            if (method.equals("dispatch")) {
                dispatchCount++;
                return storage.runs.get(args[0]);
            }
            return defaultValue(method);
        });
        service = new AgentTaskScheduleServiceImpl(storage, agentStorage, runtimeStorage,
                runtimeService, runService, coordinator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void dispatchesOnlyLatestOccurrenceInsideLatenessWindow() {
        AgentTaskSchedule schedule = cronSchedule(Date.from(Instant.parse("2026-08-17T10:00:00Z")));
        storage.schedule = schedule;

        assertEquals(1, service.dispatchDue(50));

        AgentTaskScheduleExecution execution = storage.executions.values().iterator().next();
        assertEquals(Date.from(Instant.parse("2026-08-17T10:04:00Z")), execution.getPlannedAt());
        assertEquals(AgentTaskScheduleExecutionStatusEnum.DISPATCHED, execution.getStatus());
        assertEquals(Date.from(Instant.parse("2026-08-17T10:05:00Z")), storage.schedule.getNextRunAt());
        assertEquals(1, storage.tasks.size());
        assertEquals(7L, storage.tasks.values().iterator().next().getCreatedBy());
        assertEquals(1, dispatchCount);
    }

    @Test
    void recordsOneMissedWindowAndAdvancesDirectlyToTheFuture() {
        AgentTaskSchedule schedule = cronSchedule(Date.from(Instant.parse("2026-08-17T09:00:00Z")));
        storage.schedule = schedule;

        service.dispatchDue(50);

        AgentTaskScheduleExecution execution = storage.executions.values().iterator().next();
        assertEquals(AgentTaskScheduleExecutionStatusEnum.SKIPPED, execution.getStatus());
        assertEquals(AgentTaskScheduleReasonCodeEnum.MISSED_WINDOW, execution.getReasonCode());
        assertEquals(Date.from(Instant.parse("2026-08-17T10:05:00Z")), storage.schedule.getNextRunAt());
        assertEquals(0, storage.tasks.size());
        assertEquals(0, dispatchCount);
    }

    @Test
    void skipsWhenPreviousScheduledTaskStillHasActiveRun() {
        storage.schedule = cronSchedule(Date.from(Instant.parse("2026-08-17T10:04:00Z")));
        storage.activeTask = true;

        service.dispatchDue(50);

        AgentTaskScheduleExecution execution = storage.executions.values().iterator().next();
        assertEquals(AgentTaskScheduleReasonCodeEnum.PREVIOUS_EXECUTION_ACTIVE, execution.getReasonCode());
        assertEquals(AgentTaskScheduleExecutionStatusEnum.SKIPPED, execution.getStatus());
        assertEquals(0, storage.tasks.size());
    }

    @Test
    void recoversCreatedQueuedRunWithoutCreatingAnotherTask() {
        storage.schedule = cronSchedule(Date.from(Instant.parse("2026-08-17T10:05:00Z")));
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(AgentRunStatusEnum.QUEUED);
        storage.runs.put(run.getId(), run);
        AgentTaskScheduleExecution execution = execution("execution-1",
                Date.from(Instant.parse("2026-08-17T10:04:00Z")));
        execution.setStatus(AgentTaskScheduleExecutionStatusEnum.TASK_CREATED);
        execution.setRunId(run.getId());
        storage.executions.put(execution.getId(), execution);

        assertEquals(1, service.recover(50));

        assertEquals(AgentTaskScheduleExecutionStatusEnum.DISPATCHED, execution.getStatus());
        assertEquals(1, dispatchCount);
        assertEquals(0, storage.tasks.size());
    }

    @Test
    void keepsPausedScheduleUndueAndRejectsExpiredOnceResume() {
        AgentTaskSchedule once = cronSchedule(null);
        once.setScheduleType(AgentTaskScheduleTypeEnum.ONCE);
        once.setCronExpression(null);
        once.setScheduledAt(Date.from(NOW.minusSeconds(60)));
        once.setNextRunAt(null);
        once.setStatus(AgentTaskScheduleStatusEnum.PAUSED);
        storage.schedule = once;

        assertThrows(IllegalStateException.class,
                () -> service.changeStatus(once.getId(), once.getRevision(), AgentTaskScheduleStatusEnum.ACTIVE));
        assertNull(storage.schedule.getNextRunAt());
        assertEquals(AgentTaskScheduleStatusEnum.PAUSED, storage.schedule.getStatus());
    }

    @Test
    void pauseResumeAndArchiveUseRevisionAndRecalculateCron() {
        storage.schedule = cronSchedule(Date.from(Instant.parse("2026-08-17T10:05:00Z")));

        AgentTaskSchedule paused = service.changeStatus("schedule-1", 1L,
                AgentTaskScheduleStatusEnum.PAUSED);
        assertNull(paused.getNextRunAt());
        AgentTaskSchedule resumed = service.changeStatus("schedule-1", 2L,
                AgentTaskScheduleStatusEnum.ACTIVE);
        assertEquals(Date.from(Instant.parse("2026-08-17T10:05:00Z")), resumed.getNextRunAt());
        AgentTaskSchedule archived = service.changeStatus("schedule-1", 3L,
                AgentTaskScheduleStatusEnum.ARCHIVED);
        assertEquals(AgentTaskScheduleStatusEnum.ARCHIVED, archived.getStatus());
        assertThrows(IllegalStateException.class, () -> service.changeStatus(
                "schedule-1", 4L, AgentTaskScheduleStatusEnum.ACTIVE));
    }

    @Test
    void validatesOnceAndCronMutualExclusion() {
        AgentTaskScheduleCreateRequest once = createRequest();
        once.setScheduleType(AgentTaskScheduleTypeEnum.ONCE);
        once.setScheduledAt(Date.from(NOW.plusSeconds(600)));
        once.setCronExpression("0 9 * * *");
        assertThrows(IllegalArgumentException.class, () -> service.create(once));

        AgentTaskScheduleCreateRequest cron = createRequest();
        cron.setScheduleType(AgentTaskScheduleTypeEnum.CRON);
        cron.setScheduledAt(Date.from(NOW.plusSeconds(600)));
        cron.setCronExpression("0 9 * * *");
        assertThrows(IllegalArgumentException.class, () -> service.create(cron));
    }

    @Test
    void recordsStableSkipReasonsWhenAgentOrScopeIsNoLongerAvailable() {
        storage.schedule = cronSchedule(Date.from(Instant.parse("2026-08-17T10:04:00Z")));
        agent.setStatus(AgentStatusEnum.ARCHIVED);
        service.dispatchDue(50);
        assertEquals(AgentTaskScheduleReasonCodeEnum.AGENT_UNAVAILABLE,
                storage.executions.values().iterator().next().getReasonCode());

        storage.executions.clear();
        storage.schedule = cronSchedule(Date.from(Instant.parse("2026-08-17T10:04:00Z")));
        AgentDataScope revoked = new AgentDataScope();
        revoked.setDataSourceId(99L);
        storage.schedule.setDataScopeSnapshot(List.of(revoked));
        agent.setStatus(AgentStatusEnum.ACTIVE);
        service.dispatchDue(50);
        assertEquals(AgentTaskScheduleReasonCodeEnum.DATA_SCOPE_REVOKED,
                storage.executions.values().iterator().next().getReasonCode());
    }

    private AgentTaskScheduleCreateRequest createRequest() {
        AgentTaskScheduleCreateRequest request = new AgentTaskScheduleCreateRequest();
        request.setName("Schedule");
        request.setTaskTitle("Analyze");
        request.setAssigneeAgentId("agent-1");
        request.setDataScopeSnapshot(List.of());
        request.setTimezone("UTC");
        request.setCreatedBy(7L);
        return request;
    }

    private AgentTaskSchedule cronSchedule(Date nextRunAt) {
        AgentTaskSchedule schedule = new AgentTaskSchedule();
        schedule.setId("schedule-1");
        schedule.setName("Every minute");
        schedule.setTaskTitle("Analyze channels");
        schedule.setAssigneeAgentId("agent-1");
        schedule.setPriority(0);
        schedule.setDataScopeSnapshot(List.of());
        schedule.setScheduleType(AgentTaskScheduleTypeEnum.CRON);
        schedule.setCronExpression("* * * * *");
        schedule.setTimezone("UTC");
        schedule.setStatus(AgentTaskScheduleStatusEnum.ACTIVE);
        schedule.setConcurrencyPolicy(AgentTaskScheduleConcurrencyPolicyEnum.SKIP);
        schedule.setCatchUpPolicy(AgentTaskScheduleCatchUpPolicyEnum.LATEST_ONLY);
        schedule.setNextRunAt(nextRunAt);
        schedule.setCreatedBy(7L);
        schedule.setGmtCreate(Date.from(NOW.minusSeconds(3600)));
        schedule.setGmtModified(Date.from(NOW.minusSeconds(3600)));
        schedule.setRevision(1L);
        return schedule;
    }

    private AgentTaskScheduleExecution execution(String id, Date plannedAt) {
        AgentTaskScheduleExecution execution = new AgentTaskScheduleExecution();
        execution.setId(id);
        execution.setScheduleId("schedule-1");
        execution.setSource(AgentTaskScheduleExecutionSourceEnum.SCHEDULE);
        execution.setPlannedAt(plannedAt);
        execution.setStatus(AgentTaskScheduleExecutionStatusEnum.CLAIMED);
        execution.setAttempt(1);
        execution.setLeaseToken("lease-" + id);
        execution.setLeaseExpiresAt(Date.from(NOW.plusSeconds(120)));
        execution.setGmtCreate(Date.from(NOW));
        execution.setGmtModified(Date.from(NOW));
        execution.setRevision(1L);
        return execution;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> invocation.invoke(method.getName(), args == null ? new Object[0] : args));
    }

    private static Object defaultValue(String method) {
        if (method.startsWith("list")) return List.of();
        if (method.startsWith("is") || method.startsWith("has")) return false;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }

    private static class MemoryScheduleStorage implements IAgentTaskScheduleStorage {
        private AgentTaskSchedule schedule;
        private final Map<String, AgentTaskScheduleExecution> executions = new LinkedHashMap<>();
        private final Map<String, AgentTask> tasks = new LinkedHashMap<>();
        private final Map<String, AgentRun> runs = new LinkedHashMap<>();
        private boolean activeTask;

        @Override
        public AgentTaskSchedule createSchedule(AgentTaskSchedule value) {
            schedule = value;
            return value;
        }

        @Override
        public AgentTaskSchedule updateSchedule(AgentTaskSchedule value, long expectedRevision) {
            if (schedule.getRevision() != expectedRevision) throw new IllegalStateException("revision changed");
            schedule = value;
            return value;
        }

        @Override
        public AgentTaskSchedule getSchedule(String id) {
            return schedule != null && schedule.getId().equals(id) ? schedule : null;
        }

        @Override
        public List<AgentTaskSchedule> listSchedules(Long createdBy) {
            return schedule == null ? List.of() : List.of(schedule);
        }

        @Override
        public List<AgentTaskSchedule> listDueSchedules(Date dueAt, int limit) {
            return schedule != null && schedule.getStatus() == AgentTaskScheduleStatusEnum.ACTIVE
                    && schedule.getNextRunAt() != null && !schedule.getNextRunAt().after(dueAt)
                    ? List.of(schedule) : List.of();
        }

        @Override
        public AgentTaskScheduleClaim claimExecution(AgentTaskScheduleExecution value, Date now) {
            AgentTaskScheduleExecution existing = executions.values().stream()
                    .filter(item -> item.getScheduleId().equals(value.getScheduleId()))
                    .filter(item -> item.getSource() == value.getSource())
                    .filter(item -> item.getPlannedAt().equals(value.getPlannedAt()))
                    .findFirst().orElse(null);
            if (existing != null) return new AgentTaskScheduleClaim(existing, false);
            executions.put(value.getId(), value);
            return new AgentTaskScheduleClaim(value, true);
        }

        @Override
        public AgentTaskScheduleExecution getExecution(String id) {
            return executions.get(id);
        }

        @Override
        public List<AgentTaskScheduleExecution> listExecutions(String scheduleId) {
            return new ArrayList<>(executions.values());
        }

        @Override
        public List<AgentTaskScheduleExecution> listRecoverableExecutions(Date now, int limit) {
            return executions.values().stream()
                    .filter(value -> value.getStatus() == AgentTaskScheduleExecutionStatusEnum.TASK_CREATED
                            || value.getStatus() == AgentTaskScheduleExecutionStatusEnum.CLAIMED
                            && value.getLeaseExpiresAt().before(now))
                    .limit(limit).toList();
        }

        @Override
        public AgentTaskCreation createScheduledTask(AgentTaskSchedule scheduleForCommit,
                                                     AgentTaskScheduleExecution execution,
                                                     AgentTask task, AgentRun run, Date nextRunAt,
                                                     long expectedScheduleRevision,
                                                     long expectedExecutionRevision, String leaseToken) {
            tasks.put(task.getId(), task);
            runs.put(run.getId(), run);
            execution.setTaskId(task.getId());
            execution.setRunId(run.getId());
            execution.setStatus(AgentTaskScheduleExecutionStatusEnum.TASK_CREATED);
            execution.setRevision(execution.getRevision() + 1);
            scheduleForCommit.setNextRunAt(nextRunAt);
            scheduleForCommit.setLastRunAt(execution.getPlannedAt());
            scheduleForCommit.setRevision(scheduleForCommit.getRevision() + 1);
            schedule = scheduleForCommit;
            return new AgentTaskCreation(task, run);
        }

        @Override
        public AgentTaskScheduleExecution finishExecutionWithoutTask(AgentTaskSchedule scheduleForCommit,
                                                                      AgentTaskScheduleExecution execution,
                                                                      Date nextRunAt,
                                                                      long expectedScheduleRevision,
                                                                      long expectedExecutionRevision,
                                                                      String leaseToken) {
            executions.put(execution.getId(), execution);
            scheduleForCommit.setNextRunAt(nextRunAt);
            scheduleForCommit.setLastRunAt(execution.getPlannedAt());
            scheduleForCommit.setRevision(scheduleForCommit.getRevision() + 1);
            schedule = scheduleForCommit;
            return execution;
        }

        @Override
        public AgentTaskScheduleExecution updateExecution(AgentTaskScheduleExecution execution,
                                                           long expectedRevision, String leaseToken) {
            executions.put(execution.getId(), execution);
            return execution;
        }

        @Override
        public boolean hasActiveExecutionTask(String scheduleId) {
            return activeTask;
        }
    }
}
