package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskLinkStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleCatchUpPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleConcurrencyPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionSourceEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleClaim;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2AgentTaskScheduleStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void claimsOnceCreatesAtomicallyAndKeepsDeletedTaskHistoryAcrossReopen() {
        Path database = tempDir.resolve("schedule-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        Date now = new Date(1_800_000_000_000L);
        AgentDefinition agent = agent(now);
        storage.createAgent(agent);
        AgentTaskSchedule schedule = schedule(agent, now);
        storage.createSchedule(schedule);

        AgentTaskScheduleExecution execution = execution(schedule, now, "execution-1", "lease-1");
        AgentTaskScheduleClaim first = storage.claimExecution(execution, now);
        AgentTaskScheduleClaim duplicate = storage.claimExecution(
                execution(schedule, now, "execution-duplicate", "lease-duplicate"), now);
        assertTrue(first.isClaimed());
        assertFalse(duplicate.isClaimed());
        assertEquals(first.getExecution().getId(), duplicate.getExecution().getId());

        AgentTask task = task(schedule, first.getExecution(), agent, now, "task-1");
        AgentRun run = run(task, agent, now, "run-1");
        task.setCurrentRunId(run.getId());
        storage.createScheduledTask(schedule, first.getExecution(), task, run,
                new Date(now.getTime() + 86_400_000L), 1L, 1L, "lease-1");

        assertEquals(AgentTaskOriginTypeEnum.SCHEDULE, storage.getTask(task.getId()).getOriginType());
        assertEquals(execution.getId(), storage.getTask(task.getId()).getOriginScheduleExecutionId());
        assertEquals(AgentTaskScheduleExecutionStatusEnum.TASK_CREATED,
                storage.getExecution(execution.getId()).getStatus());
        assertEquals(AgentTaskStatusEnum.TODO, storage.getExecution(execution.getId()).getTaskStatus());
        assertEquals(AgentRunStatusEnum.QUEUED, storage.getExecution(execution.getId()).getRunStatus());

        AgentTask archived = storage.getTask(task.getId());
        archived.setArchivedAt(new Date(now.getTime() + 1));
        archived.setGmtModified(archived.getArchivedAt());
        archived.setRevision(2L);
        storage.updateTask(archived, 1L);
        assertEquals(AgentTaskLinkStateEnum.ARCHIVED,
                storage.getExecution(execution.getId()).getTaskLinkState());
        storage.deleteTask(task.getId(), 2L);
        assertEquals(AgentTaskLinkStateEnum.DELETED,
                storage.getExecution(execution.getId()).getTaskLinkState());

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        assertNotNull(reopened.getSchedule(schedule.getId()));
        assertEquals(AgentTaskLinkStateEnum.DELETED,
                reopened.getExecution(execution.getId()).getTaskLinkState());
    }

    @Test
    void rollsBackTaskAndRunWhenScheduleRevisionChanges() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("schedule-rollback"));
        Date now = new Date(1_800_000_000_000L);
        AgentDefinition agent = agent(now);
        storage.createAgent(agent);
        AgentTaskSchedule schedule = schedule(agent, now);
        storage.createSchedule(schedule);
        AgentTaskScheduleExecution execution = execution(schedule, now, "execution-2", "lease-2");
        AgentTaskScheduleExecution claimed = storage.claimExecution(execution, now).getExecution();
        AgentTask task = task(schedule, claimed, agent, now, "task-rollback");
        AgentRun run = run(task, agent, now, "run-rollback");
        task.setCurrentRunId(run.getId());

        assertThrows(RuntimeException.class, () -> storage.createScheduledTask(
                schedule, claimed, task, run, null, 99L, claimed.getRevision(), "lease-2"));
        assertNull(storage.getTask(task.getId()));
        assertNull(storage.getRun(run.getId()));
        assertEquals(AgentTaskScheduleExecutionStatusEnum.CLAIMED,
                storage.getExecution(claimed.getId()).getStatus());
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndExpiredLeaseUsesFencing() throws Exception {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("schedule-lease"));
        Date plannedAt = new Date(1_800_000_000_000L);
        AgentDefinition agent = agent(plannedAt);
        storage.createAgent(agent);
        AgentTaskSchedule schedule = schedule(agent, plannedAt);
        storage.createSchedule(schedule);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskScheduleClaim> first = executor.submit(() -> {
                start.await();
                return storage.claimExecution(execution(schedule, plannedAt, "execution-a", "lease-a"), plannedAt);
            });
            Future<AgentTaskScheduleClaim> second = executor.submit(() -> {
                start.await();
                return storage.claimExecution(execution(schedule, plannedAt, "execution-b", "lease-b"), plannedAt);
            });
            start.countDown();
            AgentTaskScheduleClaim firstClaim = first.get();
            AgentTaskScheduleClaim secondClaim = second.get();
            assertEquals(1, List.of(firstClaim, secondClaim).stream()
                    .filter(AgentTaskScheduleClaim::isClaimed).count());

            AgentTaskScheduleExecution claimed = firstClaim.isClaimed()
                    ? firstClaim.getExecution() : secondClaim.getExecution();
            Date afterExpiry = new Date(claimed.getLeaseExpiresAt().getTime() + 1);
            AgentTaskScheduleExecution replacement = execution(schedule, plannedAt,
                    "execution-replacement", "lease-replacement");
            replacement.setLeaseExpiresAt(new Date(afterExpiry.getTime() + 120_000L));
            AgentTaskScheduleClaim reclaimed = storage.claimExecution(replacement, afterExpiry);
            assertTrue(reclaimed.isClaimed());
            assertEquals(2, reclaimed.getExecution().getAttempt());
            assertEquals("lease-replacement", reclaimed.getExecution().getLeaseToken());

            claimed.setStatus(AgentTaskScheduleExecutionStatusEnum.FAILED);
            claimed.setRevision(claimed.getRevision() + 1);
            assertThrows(ConcurrentModificationException.class, () -> storage.updateExecution(
                    claimed, claimed.getRevision() - 1, claimed.getLeaseToken()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scheduleUpdateRequiresCurrentRevision() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("schedule-revision"));
        Date now = new Date(1_800_000_000_000L);
        AgentDefinition agent = agent(now);
        storage.createAgent(agent);
        AgentTaskSchedule schedule = schedule(agent, now);
        storage.createSchedule(schedule);
        schedule.setName("Updated");
        schedule.setRevision(2L);

        assertEquals("Updated", storage.updateSchedule(schedule, 1L).getName());
        assertThrows(ConcurrentModificationException.class,
                () -> storage.updateSchedule(schedule, 1L));
    }

    private AgentDefinition agent(Date now) {
        AgentDefinition agent = new AgentDefinition();
        agent.setId("agent-1");
        agent.setName("Schedule Agent");
        agent.setStatus(AgentStatusEnum.ACTIVE);
        agent.setRuntimeType(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI);
        agent.setCapabilities(new LinkedHashSet<>());
        agent.setDataScopes(List.of());
        agent.setCreatedBy(1L);
        agent.setGmtCreate(now);
        agent.setGmtModified(now);
        agent.setRevision(1L);
        return agent;
    }

    private AgentTaskSchedule schedule(AgentDefinition agent, Date now) {
        AgentTaskSchedule schedule = new AgentTaskSchedule();
        schedule.setId("schedule-1");
        schedule.setName("Daily report");
        schedule.setTaskTitle("Analyze channels");
        schedule.setAssigneeAgentId(agent.getId());
        schedule.setPriority(0);
        schedule.setDataScopeSnapshot(List.of());
        schedule.setScheduleType(AgentTaskScheduleTypeEnum.CRON);
        schedule.setCronExpression("0 9 * * *");
        schedule.setTimezone("Asia/Shanghai");
        schedule.setStatus(AgentTaskScheduleStatusEnum.ACTIVE);
        schedule.setConcurrencyPolicy(AgentTaskScheduleConcurrencyPolicyEnum.SKIP);
        schedule.setCatchUpPolicy(AgentTaskScheduleCatchUpPolicyEnum.LATEST_ONLY);
        schedule.setNextRunAt(now);
        schedule.setCreatedBy(1L);
        schedule.setGmtCreate(now);
        schedule.setGmtModified(now);
        schedule.setRevision(1L);
        return schedule;
    }

    private AgentTaskScheduleExecution execution(AgentTaskSchedule schedule, Date now,
                                                 String id, String lease) {
        AgentTaskScheduleExecution execution = new AgentTaskScheduleExecution();
        execution.setId(id);
        execution.setScheduleId(schedule.getId());
        execution.setSource(AgentTaskScheduleExecutionSourceEnum.SCHEDULE);
        execution.setPlannedAt(now);
        execution.setStatus(AgentTaskScheduleExecutionStatusEnum.CLAIMED);
        execution.setAttempt(1);
        execution.setLeaseToken(lease);
        execution.setLeaseExpiresAt(new Date(now.getTime() + 120_000L));
        execution.setGmtCreate(now);
        execution.setGmtModified(now);
        execution.setRevision(1L);
        return execution;
    }

    private AgentTask task(AgentTaskSchedule schedule, AgentTaskScheduleExecution execution,
                           AgentDefinition agent, Date now, String id) {
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setTitle(schedule.getTaskTitle());
        task.setStatus(AgentTaskStatusEnum.TODO);
        task.setPriority(0);
        task.setAssigneeAgentId(agent.getId());
        task.setCreatedBy(1L);
        task.setOriginType(AgentTaskOriginTypeEnum.SCHEDULE);
        task.setOriginScheduleId(schedule.getId());
        task.setOriginScheduleExecutionId(execution.getId());
        task.setPlannedAt(execution.getPlannedAt());
        task.setDataScopeSnapshot(List.of());
        task.setGmtCreate(now);
        task.setGmtModified(now);
        task.setRevision(1L);
        return task;
    }

    private AgentRun run(AgentTask task, AgentDefinition agent, Date now, String id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setTaskId(task.getId());
        run.setAgentId(agent.getId());
        run.setRuntimeType(agent.getRuntimeType());
        run.setTriggerType(AgentRunTriggerTypeEnum.SCHEDULED);
        run.setStatus(AgentRunStatusEnum.QUEUED);
        run.setAttempt(1);
        run.setGmtCreate(now);
        run.setGmtModified(now);
        run.setRevision(1L);
        return run;
    }
}
