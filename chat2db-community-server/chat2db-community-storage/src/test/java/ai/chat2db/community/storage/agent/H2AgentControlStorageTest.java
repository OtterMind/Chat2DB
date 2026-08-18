package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentDeliveryStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentGatewayPlatformEnum;
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
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentExternalConversationBinding;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessage;
import com.alibaba.fastjson2.JSON;
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
    void persistsGatewayInboundIdempotencyAndDeliveryOutboxAcrossReopen() {
        Path database = tempDir.resolve("gateway-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        Date now = new Date(1_700_000_000_000L);
        AgentDefinition agent = agent("gateway-agent-1");
        storage.createAgent(agent);

        AgentGatewayChannel channel = new AgentGatewayChannel();
        channel.setId("gateway-channel-1"); channel.setName("Feishu local bridge");
        channel.setPlatform(AgentGatewayPlatformEnum.FEISHU); channel.setInstallationRef("local-ref-1");
        channel.setDefaultAgentId(agent.getId()); channel.setCreatedBy(1L); channel.setEnabled(true);
        channel.setGmtCreate(now); channel.setGmtModified(now); channel.setRevision(1L);
        storage.createGatewayChannel(channel, "a".repeat(64));
        assertTrue(storage.matchesGatewayToken(channel.getId(), "a".repeat(64)));

        AgentExternalConversationBinding binding = new AgentExternalConversationBinding();
        binding.setId("binding-1"); binding.setChannelId(channel.getId()); binding.setChatId("chat-1");
        binding.setThreadId("thread-1"); binding.setSessionId("session-1");
        binding.setGmtCreate(now); binding.setGmtModified(now); binding.setRevision(1L);
        storage.createConversationBinding(binding);

        AgentInboundMessage inbound = new AgentInboundMessage();
        inbound.setId("inbound-1"); inbound.setChannelId(channel.getId()); inbound.setBindingId(binding.getId());
        inbound.setEventId("event-1"); inbound.setMessageId("message-1"); inbound.setIdempotencyKey("event-1");
        inbound.setSenderId("sender-1"); inbound.setText("@AnalysisAgent analyze revenue");
        inbound.setMentions(List.of("AnalysisAgent")); inbound.setAgentId(agent.getId()); inbound.setReceivedAt(now);
        inbound.setGmtCreate(now); inbound.setGmtModified(now); inbound.setRevision(1L);
        AgentInboundMessage persisted = storage.createInboundMessage(inbound);
        AgentInboundMessage duplicate = storage.createInboundMessage(inbound);
        assertEquals(persisted.getId(), duplicate.getId());
        persisted = storage.attachInboundTask(inbound.getId(), "task-1", 1L);

        AgentDeliveryCommand delivery = new AgentDeliveryCommand();
        delivery.setId("delivery-1"); delivery.setChannelId(channel.getId());
        delivery.setInboundMessageId(inbound.getId()); delivery.setTaskId("task-1"); delivery.setRunId("run-1");
        delivery.setPlatform(channel.getPlatform()); delivery.setInstallationRef(channel.getInstallationRef());
        delivery.setChatId(binding.getChatId()); delivery.setThreadId(binding.getThreadId());
        delivery.setReplyToMessageId(inbound.getMessageId()); delivery.setContent("analysis complete");
        delivery.setIdempotencyKey("task:task-1:final"); delivery.setStatus(AgentDeliveryStatusEnum.PENDING);
        delivery.setAttemptCount(0); delivery.setNextAttemptAt(now); delivery.setGmtCreate(now);
        delivery.setGmtModified(now); delivery.setRevision(1L);
        storage.createOrGetDelivery(delivery);
        List<AgentDeliveryCommand> claimed = storage.claimDeliveries(
                channel.getId(), now, new Date(now.getTime() + 60_000L), 10);
        assertEquals(1, claimed.size());
        assertEquals(AgentDeliveryStatusEnum.DELIVERING, claimed.get(0).getStatus());
        assertEquals(1, claimed.get(0).getAttemptCount());
        assertEquals(List.of(), storage.listInboundMessagesAwaitingDelivery(channel.getId()));

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        assertEquals("task-1", reopened.getInboundMessage(channel.getId(), "event-1").getTaskId());
        assertEquals("chat-1", reopened.getConversationBinding(binding.getId()).getChatId());
        AgentDeliveryCommand delivered = reopened.getDelivery(delivery.getId());
        delivered.setStatus(AgentDeliveryStatusEnum.DELIVERED);
        delivered.setLeaseExpiresAt(null); delivered.setPlatformMessageId("feishu-message-2");
        delivered.setDeliveredAt(new Date(now.getTime() + 1_000L));
        delivered.setGmtModified(delivered.getDeliveredAt()); delivered.setRevision(delivered.getRevision() + 1);
        assertEquals(AgentDeliveryStatusEnum.DELIVERED,
                reopened.updateDelivery(delivered, delivered.getRevision() - 1).getStatus());
        assertEquals(List.of(), reopened.claimDeliveries(channel.getId(),
                new Date(now.getTime() + 120_000L), new Date(now.getTime() + 180_000L), 10));
        assertEquals(2L, persisted.getRevision());
    }

    @Test
    void persistsRuntimeProfilesAndInstancesAcrossStorageReopen() {
        Path database = tempDir.resolve("runtime-control-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        Date now = new Date(1_700_000_000_000L);
        AgentRuntimeProfile profile = runtimeProfile(now);
        AgentRuntimeInstance instance = runtimeInstance(now);

        storage.createRuntimeProfile(profile);
        storage.createRuntimeInstance(instance);

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        AgentRuntimeProfile persistedProfile = reopened.getRuntimeProfile(profile.getId());
        AgentRuntimeInstance persistedInstance = reopened.findRuntimeInstance(
                instance.getDaemonId(), instance.getProvider());
        assertEquals(AgentRuntimeTransportEnum.EXTERNAL_DAEMON, persistedProfile.getTransport());
        assertEquals(AgentRuntimeProviderEnum.CODEX, persistedProfile.getProvider());
        assertEquals(List.of("--sandbox", "workspace-write"), persistedProfile.getCustomArguments());
        assertEquals("secret:openai", persistedProfile.getEnvironmentReferences().get("OPENAI_API_KEY"));
        assertEquals(instance.getId(), persistedInstance.getId());
        assertEquals(instance.getCapabilities(), persistedInstance.getCapabilities());

        profile.setMaxConcurrency(3);
        profile.setRevision(2L);
        profile.setGmtModified(new Date(now.getTime() + 1_000L));
        AgentRuntimeProfile updated = reopened.updateRuntimeProfile(profile, 1L);
        assertEquals(3, updated.getMaxConcurrency());
        assertThrows(ConcurrentModificationException.class,
                () -> reopened.updateRuntimeProfile(profile, 1L));
    }

    @Test
    void persistsRuntimeApprovalIdempotentlyAndProtectsDecisionRevision() {
        Path database = tempDir.resolve("runtime-approval-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        Date requestedAt = new Date(1_700_000_000_000L);
        AgentRuntimeApproval approval = runtimeApproval("external-run-1", requestedAt);

        AgentRuntimeApproval created = storage.createOrGetRuntimeApproval(approval);
        AgentRuntimeApproval duplicate = storage.createOrGetRuntimeApproval(approval);
        assertEquals(created.getId(), duplicate.getId());

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        AgentRuntimeApproval persisted = reopened.findRuntimeApproval(
                approval.getRunId(), approval.getLeaseAttempt(), approval.getProviderRequestId());
        assertEquals(Map.of("command", "git status"), persisted.getRequestPayload());
        assertEquals(List.of(approval.getId()), reopened.listRuntimeApprovals(approval.getRunId())
                .stream().map(AgentRuntimeApproval::getId).toList());

        persisted.setStatus(AgentRuntimeApprovalStatusEnum.APPROVED);
        persisted.setDecision(AgentApprovalDecisionEnum.APPROVE);
        persisted.setDecidedBy(42L);
        persisted.setDecidedAt(new Date(requestedAt.getTime() + 1_000L));
        persisted.setReason("approved in task board");
        persisted.setRevision(2L);
        AgentRuntimeApproval decided = reopened.updateRuntimeApproval(persisted, 1L);
        assertEquals(AgentRuntimeApprovalStatusEnum.APPROVED, decided.getStatus());
        assertEquals(AgentApprovalDecisionEnum.APPROVE, decided.getDecision());
        assertEquals(2L, decided.getRevision());
        assertThrows(ConcurrentModificationException.class,
                () -> reopened.updateRuntimeApproval(persisted, 1L));
    }

    @Test
    void atomicallyClaimsExternalRunAndStartsItWithLeaseFencing() {
        Path database = tempDir.resolve("runtime-lease-store");
        H2AgentControlStorage storage = new H2AgentControlStorage(database);
        Date now = new Date(1_700_000_000_000L);
        AgentRuntimeProfile profile = runtimeProfile(now);
        AgentRuntimeInstance instance = runtimeInstance(now);
        instance.setMaxConcurrency(1);
        AgentDefinition agent = agent("external-agent-1");
        agent.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        agent.setRuntimeProfileId(profile.getId());
        AgentTask task = task("external-task-1", agent.getId(), "external-run-1");
        AgentRun run = run("external-run-1", task.getId(), agent.getId());
        run.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        run.setRuntimeProfileId(profile.getId());
        run.setRuntimeProvider(profile.getProvider());
        run.setRuntimeProfileSnapshot(JSON.toJSONString(profile));

        storage.createRuntimeProfile(profile);
        storage.createRuntimeInstance(instance);
        storage.createAgent(agent);
        storage.createTaskWithInitialRun(task, run);

        AgentRuntimeRunLease claimed = storage.claimRuntimeRun(instance.getId(), instance.getProvider(),
                "a".repeat(64), "b".repeat(64), now, new Date(now.getTime() + 60_000L));

        assertEquals(run.getId(), claimed.getRunId());
        assertEquals(1, claimed.getLeaseAttempt());
        assertEquals(AgentRunStatusEnum.DISPATCHED, storage.getRun(run.getId()).getStatus());
        assertEquals(2L, storage.getRun(run.getId()).getRevision());
        assertEquals(1, storage.getRuntimeInstance(instance.getId()).getActiveRuns());
        AgentRuntimeInstance heartbeated = storage.heartbeatRuntimeInstance(instance.getId(),
                instance.getDaemonId(), AgentRuntimeInstanceStatusEnum.DEGRADED,
                new Date(now.getTime() + 500L));
        assertEquals(1, heartbeated.getActiveRuns());
        assertEquals(AgentRuntimeInstanceStatusEnum.DEGRADED, heartbeated.getStatus());
        assertNull(storage.claimRuntimeRun(instance.getId(), instance.getProvider(),
                "c".repeat(64), "d".repeat(64), now, new Date(now.getTime() + 60_000L)));

        claimed.setStartedAt(new Date(now.getTime() + 1_000L));
        claimed.setLastRenewedAt(claimed.getStartedAt());
        claimed.setLeaseExpiresAt(new Date(now.getTime() + 61_000L));
        claimed.setRuntimeExecutionId("codex-process-1");
        claimed.setRevision(2L);
        AgentRuntimeRunLease started = storage.startRuntimeRun(claimed, 1L, 2L);

        assertEquals("codex-process-1", started.getRuntimeExecutionId());
        assertEquals(AgentRunStatusEnum.RUNNING, storage.getRun(run.getId()).getStatus());
        assertEquals(3L, storage.getRun(run.getId()).getRevision());

        AgentRunEvent firstEvent = event("external-1-message-1", run.getId(), "partial response");
        firstEvent.setType(ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum.SESSION_UPDATED);
        AgentRunEvent persistedEvent = storage.appendRuntimeRunEvent(
                firstEvent, 1, 1L, new Date(now.getTime() + 2_000L), "codex-thread-1");
        AgentRunEvent duplicateEvent = storage.appendRuntimeRunEvent(
                firstEvent, 1, 1L, new Date(now.getTime() + 2_000L), "codex-thread-1");
        assertEquals(persistedEvent.getSequence(), duplicateEvent.getSequence());
        assertEquals(1, persistedEvent.getRuntimeAttempt());
        assertEquals(1L, persistedEvent.getRuntimeSequence());

        AgentRunEvent outOfOrder = event("external-1-message-3", run.getId(), "gap");
        assertThrows(IllegalStateException.class, () -> storage.appendRuntimeRunEvent(
                outOfOrder, 1, 3L, new Date(now.getTime() + 3_000L), null));

        H2AgentControlStorage reopened = new H2AgentControlStorage(database);
        assertEquals(3L, reopened.getRuntimeRunLease(run.getId()).getRevision());
        assertEquals(1L, reopened.getRuntimeRunLease(run.getId()).getLastEventSequence());
        assertEquals("codex-process-1", reopened.getRuntimeRunLease(run.getId()).getRuntimeExecutionId());
        assertEquals("codex-thread-1", reopened.getRun(run.getId()).getProviderSessionId());
    }

    @Test
    void rollsBackRunClaimWhenRuntimeCapacityIsExhausted() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("runtime-capacity-store"));
        Date now = new Date(1_700_000_000_000L);
        AgentRuntimeProfile profile = runtimeProfile(now);
        AgentRuntimeInstance instance = runtimeInstance(now);
        instance.setMaxConcurrency(1);
        instance.setActiveRuns(1);
        AgentDefinition agent = agent("external-agent-1");
        agent.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        agent.setRuntimeProfileId(profile.getId());
        AgentTask task = task("external-task-1", agent.getId(), "external-run-1");
        AgentRun run = run("external-run-1", task.getId(), agent.getId());
        run.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        run.setRuntimeProfileId(profile.getId());
        run.setRuntimeProvider(profile.getProvider());
        run.setRuntimeProfileSnapshot(JSON.toJSONString(profile));
        storage.createRuntimeProfile(profile);
        storage.createRuntimeInstance(instance);
        storage.createAgent(agent);
        storage.createTaskWithInitialRun(task, run);

        assertThrows(ConcurrentModificationException.class,
                () -> storage.claimRuntimeRun(instance.getId(), instance.getProvider(),
                        "a".repeat(64), "b".repeat(64), now, new Date(now.getTime() + 60_000L)));

        assertEquals(AgentRunStatusEnum.QUEUED, storage.getRun(run.getId()).getStatus());
        assertEquals(1L, storage.getRun(run.getId()).getRevision());
        assertNull(storage.getRuntimeRunLease(run.getId()));
    }

    @Test
    void atomicallyCompletesRuntimeRunPersistsTerminalEventAndReleasesSlot() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("runtime-terminal-store"));
        Date now = new Date(1_700_000_000_000L);
        ExternalRuntimeFixture fixture = externalRuntimeFixture(storage, now);
        AgentRuntimeRunLease claimed = storage.claimRuntimeRun(fixture.instance().getId(),
                fixture.instance().getProvider(), "a".repeat(64), "b".repeat(64), now,
                new Date(now.getTime() + 60_000L));
        AgentRuntimeRunLease started = start(storage, claimed, new Date(now.getTime() + 1_000L));
        AgentRuntimeApproval pending = runtimeApproval(fixture.run().getId(), now);
        storage.createOrGetRuntimeApproval(pending);
        AgentRunEvent completed = event("external-terminal-1", fixture.run().getId(), "COMPLETED");
        completed.setRuntimeAttempt(1);
        completed.setRuntimeSequence(1L);

        AgentRuntimeRunLease terminal = storage.finishRuntimeRun(started, completed,
                AgentRunStatusEnum.COMPLETED, null, "final analysis",
                new Date(now.getTime() + 2_000L), started.getRevision(),
                storage.getRun(fixture.run().getId()).getRevision());

        assertEquals(AgentRuntimeLeaseStateEnum.COMPLETED, terminal.getState());
        assertEquals(completed.getEventId(), terminal.getTerminalEventId());
        assertNotNull(terminal.getReleasedAt());
        assertEquals(AgentRunStatusEnum.COMPLETED, storage.getRun(fixture.run().getId()).getStatus());
        assertEquals("final analysis", storage.getRun(fixture.run().getId()).getResultSummary());
        assertEquals(0, storage.getRuntimeInstance(fixture.instance().getId()).getActiveRuns());
        assertEquals(1, storage.listRunEvents(fixture.run().getId()).size());
        assertEquals(AgentRuntimeApprovalStatusEnum.EXPIRED,
                storage.getRuntimeApproval(pending.getId()).getStatus());

        AgentRuntimeRunLease duplicate = storage.finishRuntimeRun(started, completed,
                AgentRunStatusEnum.COMPLETED, null, "final analysis",
                new Date(now.getTime() + 2_000L), started.getRevision(),
                storage.getRun(fixture.run().getId()).getRevision());
        assertEquals(terminal.getRevision(), duplicate.getRevision());
        assertEquals(0, storage.getRuntimeInstance(fixture.instance().getId()).getActiveRuns());
    }

    @Test
    void requeuesUnstartedExpiredLeaseAndResetsEventSequenceForNextAttempt() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("runtime-requeue-store"));
        Date now = new Date(1_700_000_000_000L);
        ExternalRuntimeFixture fixture = externalRuntimeFixture(storage, now);
        storage.claimRuntimeRun(fixture.instance().getId(), fixture.instance().getProvider(),
                "a".repeat(64), "b".repeat(64), now, new Date(now.getTime() + 1_000L));

        assertEquals(List.of(fixture.run().getId()),
                storage.reconcileExpiredRuntimeRuns(new Date(now.getTime() + 2_000L), 10));
        assertEquals(AgentRunStatusEnum.QUEUED, storage.getRun(fixture.run().getId()).getStatus());
        assertEquals(AgentRuntimeLeaseStateEnum.EXPIRED,
                storage.getRuntimeRunLease(fixture.run().getId()).getState());
        assertEquals(0, storage.getRuntimeInstance(fixture.instance().getId()).getActiveRuns());

        AgentRuntimeRunLease second = storage.claimRuntimeRun(fixture.instance().getId(),
                fixture.instance().getProvider(), "c".repeat(64), "d".repeat(64),
                new Date(now.getTime() + 3_000L), new Date(now.getTime() + 63_000L));
        assertEquals(2, second.getLeaseAttempt());
        assertEquals(0L, second.getLastEventSequence());
        assertEquals(AgentRuntimeLeaseStateEnum.ACTIVE, second.getState());
        assertEquals(1, storage.getRuntimeInstance(fixture.instance().getId()).getActiveRuns());
    }

    @Test
    void marksStartedExpiredLeaseUnknownAndHonorsPendingCancellation() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("runtime-expiry-store"));
        Date now = new Date(1_700_000_000_000L);
        ExternalRuntimeFixture fixture = externalRuntimeFixture(storage, now);
        AgentRuntimeRunLease claimed = storage.claimRuntimeRun(fixture.instance().getId(),
                fixture.instance().getProvider(), "a".repeat(64), "b".repeat(64), now,
                new Date(now.getTime() + 2_000L));
        start(storage, claimed, new Date(now.getTime() + 1_000L));
        AgentRuntimeApproval pending = runtimeApproval(fixture.run().getId(), now);
        storage.createOrGetRuntimeApproval(pending);

        storage.reconcileExpiredRuntimeRuns(new Date(now.getTime() + 3_000L), 10);

        assertEquals(AgentRunStatusEnum.UNKNOWN, storage.getRun(fixture.run().getId()).getStatus());
        assertTrue(storage.getRun(fixture.run().getId()).getFailureReason().contains("lease expired"));
        assertEquals(AgentRuntimeLeaseStateEnum.EXPIRED,
                storage.getRuntimeRunLease(fixture.run().getId()).getState());
        assertEquals(0, storage.getRuntimeInstance(fixture.instance().getId()).getActiveRuns());
        assertEquals(AgentRuntimeApprovalStatusEnum.EXPIRED,
                storage.getRuntimeApproval(pending.getId()).getStatus());

        H2AgentControlStorage cancelStorage = new H2AgentControlStorage(
                tempDir.resolve("runtime-expiry-cancel-store"));
        ExternalRuntimeFixture cancelFixture = externalRuntimeFixture(cancelStorage, now);
        cancelStorage.claimRuntimeRun(cancelFixture.instance().getId(), cancelFixture.instance().getProvider(),
                "c".repeat(64), "d".repeat(64), now, new Date(now.getTime() + 1_000L));
        cancelStorage.requestRuntimeRunCancellation(cancelFixture.run().getId(),
                new Date(now.getTime() + 500L));
        cancelStorage.reconcileExpiredRuntimeRuns(new Date(now.getTime() + 2_000L), 10);
        assertEquals(AgentRunStatusEnum.CANCELLED,
                cancelStorage.getRun(cancelFixture.run().getId()).getStatus());
        assertEquals(0, cancelStorage.getRuntimeInstance(cancelFixture.instance().getId()).getActiveRuns());
    }

    @Test
    void suspendsExpiredLeaseWhileSqlApprovalIsPendingInsteadOfMarkingOutcomeUnknown() {
        H2AgentControlStorage storage = new H2AgentControlStorage(
                tempDir.resolve("runtime-sql-approval-expiry-store"));
        Date now = new Date(1_700_000_000_000L);
        ExternalRuntimeFixture fixture = externalRuntimeFixture(storage, now);
        AgentRuntimeRunLease claimed = storage.claimRuntimeRun(fixture.instance().getId(),
                fixture.instance().getProvider(), "a".repeat(64), "b".repeat(64), now,
                new Date(now.getTime() + 2_000L));
        start(storage, claimed, new Date(now.getTime() + 1_000L));
        AgentRun waiting = storage.getRun(fixture.run().getId());
        long waitingRevision = waiting.getRevision();
        waiting.setStatus(AgentRunStatusEnum.WAITING_APPROVAL);
        waiting.setGmtModified(new Date(now.getTime() + 1_100L));
        waiting.setRevision(waitingRevision + 1);
        storage.updateRun(waiting, waitingRevision);
        AgentSqlProposal proposal = proposal(fixture.run().getId());
        storage.createSqlProposal(proposal, approval(proposal));

        assertEquals(List.of(fixture.run().getId()),
                storage.reconcileExpiredRuntimeRuns(new Date(now.getTime() + 3_000L), 10));

        assertEquals(AgentRunStatusEnum.WAITING_APPROVAL,
                storage.getRun(fixture.run().getId()).getStatus());
        assertEquals(AgentRuntimeLeaseStateEnum.SUSPENDED,
                storage.getRuntimeRunLease(fixture.run().getId()).getState());
        assertNull(storage.getRun(fixture.run().getId()).getCompletedAt());
        assertNull(storage.getRun(fixture.run().getId()).getFailureReason());
        assertEquals(0, storage.getRuntimeInstance(fixture.instance().getId()).getActiveRuns());
    }

    @Test
    void reconcilesOrphanLeaseAfterRunAlreadyBecameTerminalAndRepairsCapacityFloor() {
        H2AgentControlStorage storage = new H2AgentControlStorage(tempDir.resolve("runtime-orphan-store"));
        Date now = new Date(1_700_000_000_000L);
        ExternalRuntimeFixture fixture = externalRuntimeFixture(storage, now);
        storage.claimRuntimeRun(fixture.instance().getId(), fixture.instance().getProvider(),
                "a".repeat(64), "b".repeat(64), now, new Date(now.getTime() + 1_000L));

        AgentRuntimeInstance inconsistent = storage.getRuntimeInstance(fixture.instance().getId());
        long instanceRevision = inconsistent.getRevision();
        inconsistent.setActiveRuns(0);
        inconsistent.setRevision(instanceRevision + 1);
        inconsistent.setGmtModified(new Date(now.getTime() + 500L));
        storage.updateRuntimeInstance(inconsistent, instanceRevision);
        AgentRun terminal = storage.getRun(fixture.run().getId());
        long runRevision = terminal.getRevision();
        terminal.setStatus(AgentRunStatusEnum.CANCELLED);
        terminal.setCompletedAt(new Date(now.getTime() + 500L));
        terminal.setGmtModified(terminal.getCompletedAt());
        terminal.setRevision(runRevision + 1);
        storage.updateRun(terminal, runRevision);

        assertEquals(List.of(fixture.run().getId()),
                storage.reconcileExpiredRuntimeRuns(new Date(now.getTime() + 2_000L), 10));
        assertEquals(AgentRunStatusEnum.CANCELLED, storage.getRun(fixture.run().getId()).getStatus());
        assertEquals(AgentRuntimeLeaseStateEnum.EXPIRED,
                storage.getRuntimeRunLease(fixture.run().getId()).getState());
        assertEquals(0, storage.getRuntimeInstance(fixture.instance().getId()).getActiveRuns());
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
        AgentArtifact artifact = artifact(task.getId(), "run-1");
        storage.createArtifact(artifact,
                artifactVersion(artifact.getId(), 1, "run-1", "archived report", null), List.of());
        task.setArchivedAt(new Date(1_700_000_000_900L));
        task.setGmtModified(task.getArchivedAt());
        task.setRevision(2L);

        AgentTask archived = storage.updateTask(task, 1L);

        assertEquals(List.of(), storage.listTasks());
        assertEquals(List.of(archived), storage.listArchivedTasks());
        assertNotNull(storage.getArtifact(artifact.getId()));
        storage.deleteTask(task.getId(), 2L);
        assertNull(storage.getTask(task.getId()));
        assertNull(storage.getRun("run-1"));
        assertNull(storage.getArtifact(artifact.getId()));
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

    private static ExternalRuntimeFixture externalRuntimeFixture(H2AgentControlStorage storage, Date now) {
        AgentRuntimeProfile profile = runtimeProfile(now);
        AgentRuntimeInstance instance = runtimeInstance(now);
        instance.setMaxConcurrency(1);
        AgentDefinition agent = agent("external-agent-1");
        agent.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        agent.setRuntimeProfileId(profile.getId());
        AgentTask task = task("external-task-1", agent.getId(), "external-run-1");
        AgentRun run = run("external-run-1", task.getId(), agent.getId());
        run.setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        run.setRuntimeProfileId(profile.getId());
        run.setRuntimeProvider(profile.getProvider());
        run.setRuntimeProfileSnapshot(JSON.toJSONString(profile));
        storage.createRuntimeProfile(profile);
        storage.createRuntimeInstance(instance);
        storage.createAgent(agent);
        storage.createTaskWithInitialRun(task, run);
        return new ExternalRuntimeFixture(instance, run);
    }

    private static AgentRuntimeApproval runtimeApproval(String runId, Date requestedAt) {
        AgentRuntimeApproval approval = new AgentRuntimeApproval();
        approval.setId("runtime-approval-1");
        approval.setRunId(runId);
        approval.setLeaseAttempt(1);
        approval.setProviderRequestId("rpc-request-1");
        approval.setToolCallId("tool-call-1");
        approval.setTitle("Allow shell command?");
        approval.setRequestPayload(Map.of("command", "git status"));
        approval.setAllowOptionId("allow-once");
        approval.setRejectOptionId("reject-once");
        approval.setStatus(AgentRuntimeApprovalStatusEnum.PENDING);
        approval.setRequestedAt(requestedAt);
        approval.setRevision(1L);
        return approval;
    }

    private static AgentRuntimeRunLease start(H2AgentControlStorage storage, AgentRuntimeRunLease claimed,
                                              Date startedAt) {
        claimed.setStartedAt(startedAt);
        claimed.setLastRenewedAt(startedAt);
        claimed.setLeaseExpiresAt(new Date(Math.max(claimed.getLeaseExpiresAt().getTime(),
                startedAt.getTime() + 1_000L)));
        claimed.setRuntimeExecutionId("runtime-execution-1");
        claimed.setRevision(claimed.getRevision() + 1);
        return storage.startRuntimeRun(claimed, claimed.getRevision() - 1,
                storage.getRun(claimed.getRunId()).getRevision());
    }

    private record ExternalRuntimeFixture(AgentRuntimeInstance instance, AgentRun run) {
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

    private static AgentRuntimeProfile runtimeProfile(Date now) {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setId("runtime-profile-1");
        profile.setName("Codex local");
        profile.setTransport(AgentRuntimeTransportEnum.EXTERNAL_DAEMON);
        profile.setProvider(AgentRuntimeProviderEnum.CODEX);
        profile.setExecutable("codex");
        profile.setWorkingDirectoryPolicy("TASK_ISOLATED");
        profile.setCustomArguments(List.of("--sandbox", "workspace-write"));
        profile.setEnvironmentReferences(Map.of("OPENAI_API_KEY", "secret:openai"));
        profile.setMcpConfiguration("{}");
        profile.setTimeoutSeconds(900);
        profile.setMaxConcurrency(1);
        profile.setSessionResumeEnabled(true);
        profile.setApprovalBridgeEnabled(false);
        profile.setEnabled(true);
        profile.setCreatedBy(1L);
        profile.setGmtCreate(now);
        profile.setGmtModified(now);
        profile.setRevision(1L);
        return profile;
    }

    private static AgentRuntimeInstance runtimeInstance(Date now) {
        AgentRuntimeInstance instance = new AgentRuntimeInstance();
        instance.setId("runtime-instance-1");
        instance.setDaemonId("daemon-local");
        instance.setProvider(AgentRuntimeProviderEnum.CODEX);
        instance.setProviderVersion("1.2.3");
        instance.setProtocolVersion("1");
        instance.setCapabilities(new LinkedHashSet<>(List.of("STREAMING", "SESSION_RESUME")));
        instance.setMaxConcurrency(2);
        instance.setActiveRuns(0);
        instance.setStatus(AgentRuntimeInstanceStatusEnum.ONLINE);
        instance.setLastHeartbeatAt(now);
        instance.setRegisteredAt(now);
        instance.setGmtModified(now);
        instance.setRevision(1L);
        return instance;
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
