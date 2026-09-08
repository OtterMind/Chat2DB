package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentApprovalScope;
import ai.chat2db.community.domain.api.model.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactType;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeId;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentSessionStatus;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.exception.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentV2StorageTest {

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-one";

    @TempDir
    Path temporaryDirectory;

    private AgentV2StoragePaths paths;
    private LocalAgentSessionStorage sessions;
    private LocalAgentRunStorage runs;
    private LocalAgentEventStorage events;
    private LocalAgentApprovalStorage approvals;
    private LocalAgentArtifactStorage artifacts;

    @BeforeEach
    void setUp() {
        paths = new AgentV2StoragePaths(temporaryDirectory.resolve("storage/ai-chat-history-v2"));
        StorageFileUtils storageFileUtils = new StorageFileUtils();
        sessions = new LocalAgentSessionStorage(paths, storageFileUtils);
        runs = new LocalAgentRunStorage(paths, storageFileUtils, sessions);
        events = new LocalAgentEventStorage(paths, storageFileUtils, sessions);
        approvals = new LocalAgentApprovalStorage(paths, storageFileUtils, sessions);
        artifacts = new LocalAgentArtifactStorage(paths, storageFileUtils, sessions);
        sessions.create(session());
    }

    @Test
    void storesAndReloadsRunsWithoutExposingOtherUsersData() {
        AgentRun accepted = run(AgentRunStatus.ACCEPTED, 1);
        runs.create(accepted, USER_ID);
        AgentRun running = run(AgentRunStatus.RUNNING, 2);
        assertTrue(runs.compareAndSet(running, AgentRunStatus.ACCEPTED, USER_ID));

        LocalAgentRunStorage reloaded = new LocalAgentRunStorage(paths, new StorageFileUtils(), sessions);

        assertEquals(running, reloaded.get(SESSION_ID, running.id(), USER_ID));
        assertEquals(List.of(running), reloaded.list(SESSION_ID, USER_ID));
        assertFalse(reloaded.compareAndSet(running, AgentRunStatus.ACCEPTED, USER_ID));
        assertNull(reloaded.get(SESSION_ID, running.id(), 2L));
        assertTrue(reloaded.list(SESSION_ID, 2L).isEmpty());
        assertTrue(Files.isRegularFile(paths.resourceFile(SESSION_ID, "runs", running.id())));
    }

    @Test
    void appendsEventsWithContinuousSequenceAndPagesAfterCursor() {
        AgentEvent first = event(1, AgentEventType.RUN_STARTED);
        AgentEvent second = event(2, AgentEventType.ASSISTANT_TEXT_DELTA);

        events.append(first, USER_ID);
        events.append(second, USER_ID);

        assertEquals(List.of(first, second), events.list(SESSION_ID, USER_ID, 0, 10));
        assertEquals(List.of(second), events.list(SESSION_ID, USER_ID, 1, 10));
        assertTrue(events.list(SESSION_ID, 2L, 0, 10).isEmpty());
        assertThrows(StorageException.class,
                () -> events.append(event(4, AgentEventType.RUN_COMPLETED), USER_ID));
        assertThrows(IllegalArgumentException.class,
                () -> events.list(SESSION_ID, USER_ID, 0, 1001));
        assertTrue(Files.isRegularFile(paths.eventFile(SESSION_ID, 2)));
    }

    @Test
    void rejectsEventSequenceGapsAfterRestart() throws Exception {
        events.append(event(1, AgentEventType.RUN_STARTED), USER_ID);
        events.append(event(2, AgentEventType.RUN_COMPLETED), USER_ID);
        Files.delete(paths.eventFile(SESSION_ID, 1));
        LocalAgentEventStorage reloaded = new LocalAgentEventStorage(paths, new StorageFileUtils(), sessions);

        assertThrows(StorageException.class, () -> reloaded.list(SESSION_ID, USER_ID, 0, 10));
        assertThrows(StorageException.class,
                () -> reloaded.append(event(3, AgentEventType.RUN_COMPLETED), USER_ID));
    }

    @Test
    void updatesApprovalDecisionWithoutChangingItsSubject() {
        AgentApproval pending = approval(AgentApprovalStatus.PENDING, "a".repeat(64));
        approvals.create(pending, USER_ID);
        AgentApproval approved = approval(AgentApprovalStatus.APPROVED, pending.subjectSha256());

        assertTrue(approvals.compareAndSet(approved, AgentApprovalStatus.PENDING, USER_ID));

        assertEquals(approved, approvals.get(SESSION_ID, approved.id(), USER_ID));
        assertEquals(List.of(approved), approvals.list(SESSION_ID, USER_ID));
        assertFalse(approvals.compareAndSet(approved, AgentApprovalStatus.PENDING, USER_ID));
        assertThrows(IllegalArgumentException.class, () -> approvals.compareAndSet(
                approval(AgentApprovalStatus.APPROVED, "b".repeat(64)),
                AgentApprovalStatus.APPROVED,
                USER_ID));
    }

    @Test
    void storesImmutableArtifactMetadataWithRelativeReferences() {
        AgentArtifact artifact = artifact("sessions/session-one/artifacts/result.csv");

        artifacts.create(artifact, USER_ID);

        assertEquals(artifact, artifacts.get(SESSION_ID, artifact.id(), USER_ID));
        assertEquals(List.of(artifact), artifacts.list(SESSION_ID, USER_ID));
        assertNull(artifacts.get(SESSION_ID, artifact.id(), 2L));
        assertThrows(IllegalArgumentException.class,
                () -> artifacts.create(artifact("../v1/result.csv"), USER_ID));
        assertThrows(IllegalArgumentException.class,
                () -> artifacts.create(artifact("C:\\temp\\result.csv"), USER_ID));
    }

    @Test
    void childStorageRejectsUnknownSessions() {
        AgentRun unknownSessionRun = new AgentRun(
                "run-two",
                "missing-session",
                AgentRunStatus.ACCEPTED,
                model(),
                "message-two",
                null,
                0,
                0,
                null,
                null);

        assertThrows(StorageException.class, () -> runs.create(unknownSessionRun, USER_ID));
    }

    private AgentSession session() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 8, 21, 0);
        return new AgentSession(
                AgentSession.SCHEMA_VERSION,
                SESSION_ID,
                USER_ID,
                "default",
                1,
                new AgentRuntimeBinding(
                        new AgentRuntimeId("pi"), "0.85.1", "jsonl-rpc", SESSION_ID, null, 1),
                AgentSessionStatus.READY,
                "Session",
                0,
                now,
                now);
    }

    private AgentRun run(AgentRunStatus status, long lastSequence) {
        return new AgentRun(
                "run-one",
                SESSION_ID,
                status,
                model(),
                "message-one",
                status == AgentRunStatus.ACCEPTED ? null : "external-run",
                1,
                lastSequence,
                null,
                null);
    }

    private AgentModelSnapshot model() {
        return new AgentModelSnapshot("model-config", 1, "openai", "gpt-test", 128000, 4096);
    }

    private AgentEvent event(long sequence, AgentEventType type) {
        Map<String, Object> payload = type == AgentEventType.ASSISTANT_TEXT_DELTA
                ? Map.of("delta", "hello") : Map.of();
        return new AgentEvent(
                "event-" + sequence,
                SESSION_ID,
                "run-one",
                sequence,
                type,
                payload,
                LocalDateTime.of(2026, 9, 8, 21, 0).plusSeconds(sequence));
    }

    private AgentApproval approval(AgentApprovalStatus status, String subjectSha256) {
        return new AgentApproval(
                "approval-one",
                SESSION_ID,
                "run-one",
                "tool-call-one",
                status,
                AgentApprovalScope.ONCE,
                subjectSha256,
                LocalDateTime.of(2026, 9, 8, 21, 5));
    }

    private AgentArtifact artifact(String reference) {
        return new AgentArtifact(
                "artifact-one",
                SESSION_ID,
                "run-one",
                AgentArtifactType.QUERY_RESULT,
                "text/csv",
                10,
                "c".repeat(64),
                reference,
                LocalDateTime.of(2026, 9, 8, 21, 2));
    }
}
