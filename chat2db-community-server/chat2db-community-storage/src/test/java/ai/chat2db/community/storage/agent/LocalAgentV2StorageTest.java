package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalScope;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactType;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.exception.storage.StorageException;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void readsExistingSessionJsonAfterRuntimeContractPackagesMove() throws Exception {
        Files.writeString(paths.sessionFile(SESSION_ID), """
                {
                  "schemaVersion": 2,
                  "id": "session-one",
                  "userId": 1,
                  "definition": {
                    "id": "default", "name": "Default", "systemPrompt": "You are helpful.",
                    "runtimeType": "PI", "modelConfigId": "model-config", "revision": 1
                  },
                  "runtimeBinding": {
                    "runtimeType": "PI", "runtimeVersion": "0.85.1", "protocolVersion": "jsonl-rpc",
                    "externalSessionId": "session-one", "resumeReference": "saved-session.jsonl", "revision": 1
                  },
                  "status": "READY", "title": "Existing session", "lastEventSequence": 0,
                  "gmtCreate": "2026-09-08T21:00:00", "gmtModified": "2026-09-08T21:00:00"
                }
                """);
        LocalAgentSessionStorage reloaded = new LocalAgentSessionStorage(paths, new StorageFileUtils());

        AgentSession existing = reloaded.get(SESSION_ID, USER_ID);

        assertEquals(AgentRuntimeType.PI, existing.definition().runtimeType());
        assertEquals("saved-session.jsonl", existing.runtimeBinding().resumeReference());
        assertEquals(AgentSessionStatus.READY, existing.status());
        AgentSession renamed = reloaded.rename(SESSION_ID, USER_ID, "Renamed session");
        assertEquals(existing.runtimeBinding(), renamed.runtimeBinding());
        assertEquals(renamed, sessions.get(SESSION_ID, USER_ID));
        assertFalse(Files.readString(paths.sessionFile(SESSION_ID)).contains("ai.chat2db"));
    }

    @Test
    void readsExistingRunAndEventJsonAfterEnumPackagesMove() throws Exception {
        Files.createDirectories(paths.resourceDirectory(SESSION_ID, "runs"));
        Files.writeString(paths.resourceFile(SESSION_ID, "runs", "run-one"), """
                {
                  "id": "run-one", "sessionId": "session-one", "status": "COMPLETED",
                  "model": {
                    "modelConfigId": "model-config", "modelRevision": 1, "provider": "openai",
                    "modelId": "gpt-test", "contextWindow": 128000, "maxOutputTokens": 4096
                  },
                  "requestMessageId": "message-one", "idempotencyKey": "idempotency-one",
                  "externalRunId": "external-run", "firstEventSequence": 1, "lastEventSequence": 1
                }
                """);
        Files.createDirectories(paths.resourceDirectory(SESSION_ID, "events"));
        Files.writeString(paths.eventFile(SESSION_ID, 1), """
                {
                  "id": "event-1", "sessionId": "session-one", "runId": "run-one", "sequence": 1,
                  "type": "RUN_COMPLETED", "payload": {}, "occurredAt": "2026-09-08T21:00:01"
                }
                """);

        assertEquals(run(AgentRunStatus.COMPLETED, 1), runs.get(SESSION_ID, "run-one", USER_ID));
        assertEquals(List.of(event(1, AgentEventType.RUN_COMPLETED)), events.list(SESSION_ID, USER_ID, 0, 10));
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
                "idempotency-two",
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
                new AgentDefinition(
                        "default", "Default", null, "You are helpful.",
                        AgentRuntimeType.PI, "model-config", 1),
                new AgentRuntimeBinding(
                        AgentRuntimeType.PI, "0.85.1", "jsonl-rpc", SESSION_ID, null, 1),
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
                "idempotency-one",
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
