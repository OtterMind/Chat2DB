package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRiskLevelEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlOperationClassEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlProposalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactVersion;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDashboardRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.h2.jdbcx.JdbcDataSource;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2AgentControlStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsEmbeddedDatabaseOpenBetweenPollingConnections() {
        JdbcDataSource dataSource = (JdbcDataSource) H2AgentControlStorage.createDataSource(
                tempDir.resolve("polling-store"));

        assertTrue(dataSource.getURL().contains("DB_CLOSE_DELAY=-1"));
        assertTrue(dataSource.getURL().contains("DB_CLOSE_ON_EXIT=FALSE"));
    }

    @Test
    void initializesWhenThreadContextClassLoaderIsNull() {
        Thread thread = Thread.currentThread();
        ClassLoader originalClassLoader = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(null);

            H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("null-context-classloader"));

            assertEquals(List.of(), storage.listAgents());
        } finally {
            thread.setContextClassLoader(originalClassLoader);
        }
    }

    @Test
    void persistsAgentTaskAndRunAcrossStorageReopen() {
        Path database = tempDir.resolve("agent-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        AgentRun run = run("run-1", task.getId(), agent.getId());

        AgentTaskCreation created = storage.createTaskWithInitialRun(task, run);

        assertEquals(task.getId(), created.getTask().getId());
        assertEquals(run.getId(), created.getInitialRun().getId());

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        AgentDefinition persistedAgent = reopened.getAgent(agent.getId());
        assertEquals(agent.getCapabilities(), persistedAgent.getCapabilities());
        assertEquals("orders", persistedAgent.getDataScopes().get(0).getTableNames().get(0));
        assertEquals(run.getId(), reopened.getTask(task.getId()).getCurrentRunId());
        assertEquals(List.of(run.getId()), reopened.listRunsByTask(task.getId()).stream().map(AgentRun::getId).toList());
    }

    @Test
    void rollsBackTaskWhenInitialRunCannotBeInserted() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("rollback-store"));
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        AgentRun invalidRun = run("run-1", task.getId(), "missing-agent");

        assertThrows(IllegalStateException.class, () -> storage.createTaskWithInitialRun(task, invalidRun));
        assertNull(storage.getTask(task.getId()));
        assertNull(storage.getRun(invalidRun.getId()));
    }

    @Test
    void rejectsDuplicateAgentName() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("unique-store"));
        storage.createAgent(agent("agent-1"));
        AgentDefinition duplicate = agent("agent-2");

        assertThrows(IllegalStateException.class, () -> storage.createAgent(duplicate));
        assertNotNull(storage.getAgent("agent-1"));
        assertNull(storage.getAgent("agent-2"));
    }

    @Test
    void updatesRunWithCompareAndSetRevision() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("run-cas-store"));
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        AgentRun run = run("run-1", task.getId(), agent.getId());
        storage.createTaskWithInitialRun(task, run);

        AgentRun updated = run("run-1", task.getId(), agent.getId());
        updated.setStatus(AgentRunStatusEnum.RUNNING);
        updated.setStartedAt(new Date(1_700_000_000_300L));
        updated.setGmtModified(new Date(1_700_000_000_300L));
        updated.setRevision(2L);

        AgentRun persisted = storage.updateRun(updated, 1L);

        assertEquals(AgentRunStatusEnum.RUNNING, persisted.getStatus());
        assertEquals(2L, persisted.getRevision());
        assertEquals(updated.getStartedAt(), persisted.getStartedAt());
        assertThrows(ConcurrentModificationException.class, () -> storage.updateRun(updated, 1L));
    }

    @Test
    void persistsTaskCasAndIdempotentOrderedRunEventsAcrossReopen() {
        Path database = tempDir.resolve("event-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        AgentRun run = run("run-1", task.getId(), agent.getId());
        storage.createTaskWithInitialRun(task, run);

        task.setStatus(AgentTaskStatusEnum.IN_PROGRESS);
        task.setDataScopeSyncedAt(new Date(1_700_000_000_350L));
        task.setDataScopeSyncedFromAgentRevision(3L);
        task.setRevision(2L);
        task.setGmtModified(new Date(1_700_000_000_400L));
        AgentTask updatedTask = storage.updateTask(task, 1L);

        AgentRunEvent first = event("event-1", run.getId(), "RUNNING");
        AgentRunEvent second = event("event-2", run.getId(), "COMPLETED");
        AgentRunEvent persistedFirst = storage.appendRunEvent(first);
        AgentRunEvent duplicateFirst = storage.appendRunEvent(first);
        storage.appendRunEvent(second);

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        List<AgentRunEvent> events = reopened.listRunEvents(run.getId());

        assertEquals(AgentTaskStatusEnum.IN_PROGRESS, updatedTask.getStatus());
        assertEquals(task.getDataScopeSnapshot(), updatedTask.getDataScopeSnapshot());
        assertEquals(task.getDataScopeSyncedAt(), updatedTask.getDataScopeSyncedAt());
        assertEquals(3L, updatedTask.getDataScopeSyncedFromAgentRevision());
        assertEquals(2L, updatedTask.getRevision());
        assertThrows(ConcurrentModificationException.class, () -> storage.updateTask(task, 1L));
        assertEquals(persistedFirst.getSequence(), duplicateFirst.getSequence());
        assertEquals(List.of("event-1", "event-2"), events.stream().map(AgentRunEvent::getEventId).toList());
        assertEquals("COMPLETED", events.get(1).getPayload().get("status"));
    }

    @Test
    void listsArchivedSeparatelyAndDeletesTaskGraph() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("archive-store"));
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        storage.createTaskWithInitialRun(task, run("run-1", task.getId(), agent.getId()));
        task.setArchivedAt(new Date(1_700_000_000_900L));
        task.setGmtModified(task.getArchivedAt());
        task.setRevision(2L);

        AgentTask archived = storage.updateTask(task, 1L);

        assertEquals(List.of(), storage.listTasks());
        assertEquals(List.of(archived), storage.listArchivedTasks());
        storage.deleteTask(task.getId(), 2L);
        assertNull(storage.getTask(task.getId()));
        assertNull(storage.getRun("run-1"));
    }

    @Test
    void persistsAppendOnlyTaskContextAcrossReopen() {
        Path database = tempDir.resolve("context-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        storage.createTaskWithInitialRun(task, run("run-1", task.getId(), agent.getId()));

        ai.chat2db.community.domain.api.model.agent.AgentTaskContext context =
                new ai.chat2db.community.domain.api.model.agent.AgentTaskContext();
        context.setId("context-1");
        context.setTaskId(task.getId());
        context.setType(ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum.PINNED);
        context.setTitle("Metric definition");
        context.setContent("Refund rate excludes test orders.");
        context.setCreatedBy(1L);
        context.setCreatedAt(new Date(1_700_000_000_700L));
        storage.appendTaskContext(context);

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);

        assertEquals(1, reopened.listTaskContexts(task.getId()).size());
        assertEquals("Refund rate excludes test orders.",
                reopened.listTaskContexts(task.getId()).get(0).getContent());
    }

    @Test
    void persistsImmutableArtifactVersionsAndEvidenceAcrossReopen() {
        Path database = tempDir.resolve("artifact-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        AgentRun run = run("run-1", task.getId(), agent.getId());
        storage.createTaskWithInitialRun(task, run);

        AgentArtifact artifact = artifact(task.getId(), run.getId());
        AgentArtifactVersion first = artifactVersion(artifact.getId(), 1, run.getId(), "Initial report", null);
        AgentArtifactEvidence evidence = artifactEvidence(artifact.getId(), run.getId());
        AgentArtifactDetail created = storage.createArtifact(artifact, first, List.of(evidence));

        AgentArtifact updated = artifact(task.getId(), run.getId());
        updated.setCurrentVersion(2);
        updated.setRevision(2L);
        updated.setGmtModified(new Date(1_700_000_001_000L));
        AgentArtifactVersion second = artifactVersion(updated.getId(), 2, run.getId(), "Updated report", 1);
        storage.appendArtifactVersion(updated, second, List.of(), 1L);

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        AgentArtifactDetail detail = new AgentArtifactDetail();
        detail.setArtifact(reopened.getArtifact(artifact.getId()));
        detail.setVersions(reopened.listArtifactVersions(artifact.getId()));
        detail.setEvidence(reopened.listArtifactEvidence(artifact.getId()));

        assertEquals(1, created.getVersions().size());
        assertEquals(2, detail.getArtifact().getCurrentVersion());
        assertEquals(List.of(1, 2), detail.getVersions().stream()
                .map(AgentArtifactVersion::getVersion).toList());
        assertEquals("Initial report", detail.getVersions().get(0).getContent().get("markdown"));
        assertEquals("select * from refunds", detail.getEvidence().get(0).getSqlSnapshot());
        assertEquals(1, detail.getEvidence().get(0).getArtifactVersion());
        assertThrows(ConcurrentModificationException.class,
                () -> storage.appendArtifactVersion(updated, second, List.of(), 1L));
    }

    @Test
    void persistsApprovalAndEnforcesToolAttemptIdempotencyAcrossReopen() {
        Path database = tempDir.resolve("approval-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        AgentRun run = run("run-1", task.getId(), agent.getId());
        storage.createTaskWithInitialRun(task, run);

        AgentSqlProposal proposal = proposal(run.getId());
        AgentApproval approval = approval(proposal);
        storage.createSqlProposal(proposal, approval);
        AgentToolAttempt first = attempt(run.getId(), proposal);
        AgentToolAttempt duplicate = attempt(run.getId(), proposal);
        AgentToolAttempt persisted = storage.createOrGetToolAttempt(first);
        AgentToolAttempt deduplicated = storage.createOrGetToolAttempt(duplicate);

        AgentToolAttempt executing = attempt(run.getId(), proposal);
        executing.setId(persisted.getId());
        executing.setStatus(AgentToolAttemptStatusEnum.EXECUTING);
        executing.setExecutingAt(new Date(1_700_000_002_000L));
        executing.setRevision(2L);
        storage.updateToolAttempt(executing, 1L);

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);

        assertEquals(persisted.getId(), deduplicated.getId());
        assertEquals(AgentApprovalStatusEnum.PENDING,
                reopened.findApprovalByProposal(proposal.getId()).getStatus());
        assertEquals(AgentToolAttemptStatusEnum.EXECUTING,
                reopened.listToolAttempts(run.getId()).get(0).getStatus());
        assertEquals(proposal.getId(), reopened.findSqlProposal(
                run.getId(), proposal.getSqlHash(), 7L, "sales", "public").getId());
        assertThrows(ConcurrentModificationException.class,
                () -> storage.updateToolAttempt(executing, 1L));
    }

    @Test
    void persistsIdempotentArtifactDashboardReferenceAcrossReopen() {
        Path database = tempDir.resolve("dashboard-ref-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        AgentDefinition agent = agent("agent-1");
        storage.createAgent(agent);
        AgentTask task = task("task-1", agent.getId(), "run-1");
        AgentRun run = run("run-1", task.getId(), agent.getId());
        storage.createTaskWithInitialRun(task, run);
        AgentArtifact artifact = artifact(task.getId(), run.getId());
        storage.createArtifact(artifact, artifactVersion(artifact.getId(), 1, run.getId(), "report", null),
                List.of());

        AgentArtifactDashboardRef reference = new AgentArtifactDashboardRef();
        reference.setId("reference-1");
        reference.setTaskId(task.getId());
        reference.setArtifactId(artifact.getId());
        reference.setArtifactVersion(1);
        reference.setChartIndex(0);
        reference.setDashboardId(11L);
        reference.setChartId(22L);
        reference.setContentMode(AgentArtifactContentModeEnum.SNAPSHOT);
        reference.setPublishedBy(1L);
        reference.setPublishedAt(new Date(1_700_000_003_000L));
        storage.createOrGetArtifactDashboardRef(reference);
        AgentArtifactDashboardRef duplicate = new AgentArtifactDashboardRef();
        duplicate.setId("reference-2");
        duplicate.setTaskId(task.getId());
        duplicate.setArtifactId(artifact.getId());
        duplicate.setArtifactVersion(1);
        duplicate.setChartIndex(0);
        duplicate.setDashboardId(11L);
        duplicate.setChartId(99L);
        duplicate.setContentMode(AgentArtifactContentModeEnum.SNAPSHOT);
        duplicate.setPublishedBy(1L);
        duplicate.setPublishedAt(new Date(1_700_000_004_000L));

        assertEquals("reference-1", storage.createOrGetArtifactDashboardRef(duplicate).getId());
        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        assertEquals(22L, reopened.listArtifactDashboardRefs(task.getId()).get(0).getChartId());
    }

    private static AgentRunEvent event(String eventId, String runId, String status) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(eventId);
        event.setRunId(runId);
        event.setType(ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum.STATUS);
        event.setContent(status);
        event.setPayload(Map.of("status", status));
        event.setOccurredAt(new Date(1_700_000_000_500L));
        event.setPersistedAt(new Date(1_700_000_000_600L));
        return event;
    }

    private static AgentArtifact artifact(String taskId, String runId) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setId("artifact-1");
        artifact.setTaskId(taskId);
        artifact.setType(AgentArtifactTypeEnum.REPORT);
        artifact.setTitle("Analysis Report");
        artifact.setStatus(AgentArtifactStatusEnum.READY);
        artifact.setCurrentVersion(1);
        artifact.setCreatedByRunId(runId);
        artifact.setCreatedBy(1L);
        artifact.setGmtCreate(new Date(1_700_000_000_700L));
        artifact.setGmtModified(new Date(1_700_000_000_700L));
        artifact.setRevision(1L);
        return artifact;
    }

    private static AgentArtifactVersion artifactVersion(String artifactId, int version, String runId,
                                                         String markdown, Integer supersedes) {
        AgentArtifactVersion artifactVersion = new AgentArtifactVersion();
        artifactVersion.setArtifactId(artifactId);
        artifactVersion.setVersion(version);
        artifactVersion.setContentMode(AgentArtifactContentModeEnum.SNAPSHOT);
        artifactVersion.setContent(Map.of("markdown", markdown));
        artifactVersion.setContentHash("a".repeat(64));
        artifactVersion.setCreatedByRunId(runId);
        artifactVersion.setCreatedAt(new Date(1_700_000_000_800L + version));
        artifactVersion.setSupersedesVersion(supersedes);
        return artifactVersion;
    }

    private static AgentArtifactEvidence artifactEvidence(String artifactId, String runId) {
        AgentArtifactEvidence evidence = new AgentArtifactEvidence();
        evidence.setId("evidence-1");
        evidence.setArtifactId(artifactId);
        evidence.setArtifactVersion(1);
        evidence.setRunId(runId);
        evidence.setDataSourceId(7L);
        evidence.setSqlSnapshot("select * from refunds");
        evidence.setSqlHash("b".repeat(64));
        evidence.setExecutedAt(new Date(1_700_000_000_850L));
        evidence.setRowCount(3L);
        evidence.setCreatedAt(new Date(1_700_000_000_900L));
        return evidence;
    }

    private static AgentSqlProposal proposal(String runId) {
        AgentSqlProposal proposal = new AgentSqlProposal();
        proposal.setId("proposal-1"); proposal.setRunId(runId); proposal.setProposalVersion(1);
        proposal.setSqlSnapshot("update refunds set status='REVIEW' where id=1");
        proposal.setSqlHash("c".repeat(64)); proposal.setDataSourceId(7L);
        proposal.setDatabaseName("sales"); proposal.setSchemaName("public");
        proposal.setOperationClass(AgentSqlOperationClassEnum.WRITE);
        proposal.setRiskLevel(AgentRiskLevelEnum.MEDIUM); proposal.setEstimatedImpact("one row");
        proposal.setStatus(AgentSqlProposalStatusEnum.ACTIVE);
        proposal.setCreatedAt(new Date(1_700_000_001_100L));
        proposal.setUpdatedAt(new Date(1_700_000_001_100L)); proposal.setRevision(1L);
        return proposal;
    }

    private static AgentApproval approval(AgentSqlProposal proposal) {
        AgentApproval approval = new AgentApproval();
        approval.setId("approval-1"); approval.setProposalId(proposal.getId());
        approval.setRunId(proposal.getRunId()); approval.setProposalVersion(proposal.getProposalVersion());
        approval.setProposalHash(proposal.getSqlHash()); approval.setStatus(AgentApprovalStatusEnum.PENDING);
        approval.setRequestedBy("agent-1"); approval.setRequestedAt(new Date(1_700_000_001_200L));
        approval.setRevision(1L);
        return approval;
    }

    private static AgentToolAttempt attempt(String runId, AgentSqlProposal proposal) {
        AgentToolAttempt attempt = new AgentToolAttempt();
        attempt.setId("attempt-" + System.nanoTime()); attempt.setRunId(runId);
        attempt.setProposalId(proposal.getId()); attempt.setProposalVersion(proposal.getProposalVersion());
        attempt.setToolCallId("tool-call-1"); attempt.setToolName("execute_sql");
        attempt.setStatus(AgentToolAttemptStatusEnum.PREPARED); attempt.setWriteOperation(true);
        attempt.setPreparedAt(new Date(1_700_000_001_300L)); attempt.setRevision(1L);
        return attempt;
    }

    private static AgentDefinition agent(String id) {
        Date now = new Date(1_700_000_000_000L);
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(7L);
        scope.setDatabaseName("sales");
        scope.setSchemaName("public");
        scope.setTableNames(List.of("orders"));

        AgentDefinition agent = new AgentDefinition();
        agent.setId(id);
        agent.setName("Data Analyst");
        agent.setStatus(AgentStatusEnum.ACTIVE);
        agent.setRuntimeType(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI);
        agent.setCapabilities(new LinkedHashSet<>(List.of(
                AgentCapabilityEnum.METADATA_READ,
                AgentCapabilityEnum.DATA_READ)));
        agent.setDataScopes(List.of(scope));
        agent.setCreatedBy(1L);
        agent.setGmtCreate(now);
        agent.setGmtModified(now);
        agent.setRevision(1L);
        return agent;
    }

    private static AgentTask task(String id, String agentId, String runId) {
        Date now = new Date(1_700_000_000_100L);
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setTitle("Analyze refunds");
        task.setStatus(AgentTaskStatusEnum.TODO);
        task.setPriority(5);
        task.setAssigneeAgentId(agentId);
        task.setCreatedBy(1L);
        task.setOriginType(AgentTaskOriginTypeEnum.CHAT);
        task.setOriginSessionId("session-1");
        task.setDataScopeSnapshot(agent(agentId).getDataScopes());
        task.setCurrentRunId(runId);
        task.setGmtCreate(now);
        task.setGmtModified(now);
        task.setRevision(1L);
        return task;
    }

    private static AgentRun run(String id, String taskId, String agentId) {
        Date now = new Date(1_700_000_000_200L);
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setTaskId(taskId);
        run.setAgentId(agentId);
        run.setRuntimeType(AgentRuntimeTypeEnum.EMBEDDED_SPRING_AI);
        run.setTriggerType(AgentRunTriggerTypeEnum.TASK_CREATED);
        run.setStatus(AgentRunStatusEnum.QUEUED);
        run.setAttempt(1);
        run.setGmtCreate(now);
        run.setGmtModified(now);
        run.setRevision(1L);
        return run;
    }
}
