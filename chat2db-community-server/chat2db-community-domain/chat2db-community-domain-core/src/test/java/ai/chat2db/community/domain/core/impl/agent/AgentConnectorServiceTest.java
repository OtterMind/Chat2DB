package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorSessionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorExecutionModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentDataWikiBinding;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairing;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorSession;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorConversation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorInvocation;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import ai.chat2db.community.domain.api.model.request.agent.AgentConnectorPairingCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentDefinitionService;
import ai.chat2db.community.domain.api.service.agent.IAgentRunService;
import ai.chat2db.community.domain.api.service.agent.IAgentTaskService;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import ai.chat2db.community.domain.api.service.storage.IAgentConnectorStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConnectorServiceTest {

    @Test
    void pairingUsesAnOpaquePollTokenAndRejectsAnotherToken() {
        MemoryStorage storage = new MemoryStorage();
        AgentConnectorServiceImpl service = service(storage);
        AgentConnectorPairingCreateRequest request = new AgentConnectorPairingCreateRequest();
        request.setClientName("DeepSeek Harness");
        request.setProtocolVersion(1);

        var ticket = service.createPairing(request);

        assertNotNull(ticket.getPollToken());
        assertNotEquals(ticket.getPollToken(), storage.pairing.getPollTokenHash());
        assertEquals("DeepSeek Harness", service.pairingStatus(ticket.getPairingId(), ticket.getPollToken()).getClientName());
        assertThrows(SecurityException.class, () -> service.pairingStatus(ticket.getPairingId(), "wrong"));
    }

    @Test
    void refreshRejectsAPlaintextThatDoesNotMatchTheStoredHash() {
        MemoryStorage storage = new MemoryStorage();
        AgentConnectorServiceImpl service = service(storage);
        var initial = session("session-1");
        storage.session = initial;

        // Seed a real refresh-token hash through the public refresh path by first issuing a pairing is
        // intentionally impossible; storage never sees plaintext. The negative path is the important boundary.
        assertThrows(SecurityException.class, () -> service.refresh("session-1", "not-the-token"));
    }

    @Test
    void tokenRefreshDoesNotPretendToBeConnectorActivity() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        AgentConnectorSession session = session("session-1");
        session.setRefreshTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("refresh-secret".getBytes(StandardCharsets.UTF_8))));
        session.setLastUsedAt(new Date(1_000L));
        storage.session = session;

        service(storage).refresh("session-1", "refresh-secret");

        assertEquals(new Date(1_000L), storage.session.getLastUsedAt());
    }

    @Test
    void contextReturnsCurrentAgentEffectiveScopeAndDataWikiPolicy() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        AgentConnectorSession session = session("session-1");
        session.setAgentId("agent-1");
        session.setRunId("run-1");
        session.setTaskId("task-1");
        session.setClientName("DeepSeek Harness");
        session.setAgentName("Finance");
        session.setAccessTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("access-secret".getBytes(StandardCharsets.UTF_8))));
        session.setAccessTokenExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        storage.session = session;

        AgentDataScope explicit = new AgentDataScope();
        explicit.setDataSourceId(7L);
        explicit.setDatabaseName("analytics");
        AgentDataScope effective = new AgentDataScope();
        effective.setDataSourceId(8L);
        effective.setDatabaseName("warehouse");
        effective.setTableNames(List.of("orders"));
        AgentDataWikiBinding binding = new AgentDataWikiBinding();
        binding.setDataWikiId("wiki-1");
        binding.setMaxRows(100);
        binding.setTimeoutSeconds(30);

        AgentDefinition agent = new AgentDefinition();
        agent.setId("agent-1");
        agent.setName("Finance");
        agent.setStatus(AgentStatusEnum.ACTIVE);
        agent.setAvatar("agent-runtime://DSH");
        agent.setCapabilities(new LinkedHashSet<>(List.of(
                AgentCapabilityEnum.METADATA_READ, AgentCapabilityEnum.DATA_READ)));
        agent.setDataScopes(List.of(explicit));
        agent.setEffectiveDataScopes(List.of(explicit, effective));
        agent.setDataWikiBindings(List.of(binding));

        DataWikiResource resource = new DataWikiResource();
        resource.setDataSourceId(8L);
        resource.setTableName("orders");
        DataWikiDefinition wiki = new DataWikiDefinition();
        wiki.setId("wiki-1");
        wiki.setName("Orders Wiki");
        wiki.setResources(List.of(resource));

        IAgentDefinitionService agents = proxy(IAgentDefinitionService.class, (method, args) -> agent);
        IAgentRunService runs = proxy(IAgentRunService.class, (method, args) -> {
            AgentRun run = new AgentRun();
            run.setId("run-1");
            run.setStatus(AgentRunStatusEnum.RUNNING);
            return run;
        });
        IDataWikiService wikis = proxy(IDataWikiService.class, (method, args) -> wiki);
        AgentConnectorServiceImpl service = new AgentConnectorServiceImpl(storage, agents, null, runs, wikis);

        var context = service.context("session-1", "access-secret");

        assertEquals("Finance", context.getAgentName());
        assertEquals("agent-runtime://DSH", context.getAgentAvatar());
        assertEquals(AgentConnectorExecutionModeEnum.EXTERNAL_RUNTIME_DELEGATION, context.getExecutionMode());
        assertEquals("DeepSeek Harness", context.getExternalRuntimeName());
        assertEquals(2, context.getEffectiveDataScopes().size());
        assertEquals(1, context.getDataWikis().size());
        assertEquals("Orders Wiki", context.getDataWikis().get(0).getName());
        assertEquals(100, context.getDataWikis().get(0).getMaxRows());

        var auditContext = service.auditContext("task-1");
        assertEquals(AgentConnectorExecutionModeEnum.EXTERNAL_RUNTIME_DELEGATION,
                auditContext.getExecutionMode());
        assertEquals("DeepSeek Harness", auditContext.getExternalRuntimeName());
        assertEquals("agent-1", auditContext.getAuthorizationAgentId());
        assertEquals("Finance", auditContext.getAuthorizationAgentName());
    }

    @Test
    void reconciliationExpiresIdleSessionAndClosesAndArchivesItsAuditTask() {
        MemoryStorage storage = new MemoryStorage();
        AgentConnectorSession session = session("session-1");
        session.setTaskId("task-1");
        session.setRunId("run-1");
        session.setLastUsedAt(new Date(1_000L));
        storage.session = session;

        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setTaskId("task-1");
        run.setStatus(AgentRunStatusEnum.RUNNING);
        run.setRevision(1L);
        AgentTask task = new AgentTask();
        task.setId("task-1");
        task.setStatus(AgentTaskStatusEnum.IN_PROGRESS);
        task.setRevision(1L);

        IAgentRunService runs = proxy(IAgentRunService.class, (method, args) -> {
            if ("get".equals(method)) return run;
            if ("transition".equals(method)) {
                AgentRunTransitionRequest request = (AgentRunTransitionRequest) args[0];
                run.setStatus(request.getTargetStatus());
                run.setRevision(run.getRevision() + 1);
                return run;
            }
            throw new UnsupportedOperationException(method);
        });
        IAgentTaskService tasks = proxy(IAgentTaskService.class, (method, args) -> {
            if ("get".equals(method)) return task;
            if ("transition".equals(method)) {
                AgentTaskTransitionRequest request = (AgentTaskTransitionRequest) args[0];
                task.setStatus(request.getTargetStatus());
                task.setRevision(task.getRevision() + 1);
                return task;
            }
            if ("archive".equals(method)) {
                task.setArchivedAt(new Date());
                task.setRevision(task.getRevision() + 1);
                return task;
            }
            throw new UnsupportedOperationException(method);
        });
        AgentConnectorServiceImpl service = new AgentConnectorServiceImpl(storage, null, tasks, runs, null);

        assertEquals(1, service.reconcileSessions(new Date(2_000L), 10));
        assertEquals(AgentConnectorSessionStatusEnum.EXPIRED, storage.session.getStatus());
        assertEquals(AgentRunStatusEnum.CANCELLED, run.getStatus());
        assertEquals(AgentTaskStatusEnum.DONE, task.getStatus());
        assertTrue(task.getArchivedAt() != null);
    }

    @Test
    void mapsEachExternalConversationToOneTaskAndEachCallToAnIdempotentRun() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        AgentConnectorSession session = session("session-1");
        session.setAgentId("agent-1"); session.setAgentName("Finance"); session.setOwnerId(7L);
        session.setAccessTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("access-secret".getBytes(StandardCharsets.UTF_8))));
        session.setAccessTokenExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        session.setLastUsedAt(new Date(1_000L));
        storage.session = session;

        AgentDefinition agent = new AgentDefinition();
        agent.setId("agent-1"); agent.setName("Finance"); agent.setCreatedBy(7L);
        agent.setStatus(AgentStatusEnum.ACTIVE); agent.setDataScopes(List.of());
        IAgentDefinitionService agents = proxy(IAgentDefinitionService.class, (method, args) -> agent);
        AtomicInteger taskSequence = new AtomicInteger();
        AtomicInteger runSequence = new AtomicInteger();
        Map<String, AgentTask> tasksById = new HashMap<>();
        Map<String, AgentRun> runsById = new HashMap<>();
        IAgentTaskService tasks = proxy(IAgentTaskService.class, (method, args) -> {
            if ("create".equals(method)) {
                String taskId = "task-" + taskSequence.incrementAndGet();
                AgentTask task = new AgentTask();
                task.setId(taskId); task.setStatus(AgentTaskStatusEnum.BACKLOG); task.setRevision(1L);
                tasksById.put(taskId, task);
                AgentRun run = run(taskId, "run-" + runSequence.incrementAndGet());
                runsById.put(run.getId(), run);
                return new AgentTaskCreation(task, run);
            }
            if ("createConnectorRun".equals(method)) {
                String taskId = (String) args[0];
                AgentRun run = run(taskId, "run-" + runSequence.incrementAndGet());
                runsById.put(run.getId(), run);
                return new AgentTaskCreation(tasksById.get(taskId), run);
            }
            if ("transition".equals(method)) {
                AgentTaskTransitionRequest request = (AgentTaskTransitionRequest) args[0];
                AgentTask task = tasksById.get(request.getTaskId());
                task.setStatus(request.getTargetStatus()); task.setRevision(task.getRevision() + 1);
                return task;
            }
            if ("get".equals(method)) return tasksById.get(args[0]);
            throw new UnsupportedOperationException(method);
        });
        IAgentRunService runs = proxy(IAgentRunService.class, (method, args) -> {
            if ("get".equals(method)) return runsById.get(args[0]);
            if ("transition".equals(method)) {
                AgentRunTransitionRequest request = (AgentRunTransitionRequest) args[0];
                AgentRun run = runsById.get(request.getRunId());
                run.setStatus(request.getTargetStatus()); run.setRevision(run.getRevision() + 1);
                run.setResultSummary(request.getResultSummary()); run.setFailureReason(request.getFailureReason());
                return run;
            }
            if ("appendEvent".equals(method)) return null;
            throw new UnsupportedOperationException(method);
        });
        AgentConnectorServiceImpl service = new AgentConnectorServiceImpl(storage, agents, tasks, runs, null);

        var first = service.authorizeToolCall("session-1", "access-secret", "chat-a", "call-1",
                "execute_sql", "{\"sql\":\"select 1\"}");
        var retry = service.authorizeToolCall("session-1", "access-secret", "chat-a", "call-1",
                "execute_sql", "{\"sql\":\"select 1\"}");
        var secondCall = service.authorizeToolCall("session-1", "access-secret", "chat-a", "call-2",
                "list_all_tables", "{}");
        var otherChat = service.authorizeToolCall("session-1", "access-secret", "chat-b", "call-1",
                "execute_sql", "{\"sql\":\"select 2\"}");

        assertEquals(first.getTaskId(), retry.getTaskId());
        assertEquals(first.getRunId(), retry.getRunId());
        assertEquals(first.getTaskId(), secondCall.getTaskId());
        assertNotEquals(first.getRunId(), secondCall.getRunId());
        assertNotEquals(first.getTaskId(), otherChat.getTaskId());
        assertTrue(storage.session.getLastUsedAt().after(new Date(1_000L)));

        runsById.get(first.getRunId()).setStatus(AgentRunStatusEnum.WAITING_APPROVAL);
        assertEquals(1, service.listSessions(7L).get(0).getPendingApprovalCount());
        assertEquals(1, service.listConversations("session-1", 7L).stream()
                .filter(value -> "chat-a".equals(value.getExternalSessionId())).findFirst().orElseThrow()
                .getPendingApprovalCount());
        runsById.get(first.getRunId()).setStatus(AgentRunStatusEnum.RUNNING);

        service.completeToolCall(first, true, false, "{\"content\":[]}");
        var completedRetry = service.authorizeToolCall("session-1", "access-secret", "chat-a", "call-1",
                "execute_sql", "{\"sql\":\"select 1\"}");
        assertEquals("{\"content\":[]}", completedRetry.getConnectorReplayResultJson());

        var legacy = service.authorizeToolCall("session-1", "access-secret", null, null,
                "execute_sql", "{\"sql\":\"select 3\"}");
        assertNotNull(storage.session.getTaskId());
        assertEquals(storage.session.getTaskId(), legacy.getTaskId());
        var legacyRetry = service.authorizeToolCall("session-1", "access-secret", null, null,
                "execute_sql", "{\"sql\":\"select 3\"}");
        assertEquals(legacy.getTaskId(), legacyRetry.getTaskId());
        assertEquals(legacy.getRunId(), legacyRetry.getRunId());

        var correlatedAfterLegacy = service.authorizeToolCall("session-1", "access-secret",
                "chat-after-legacy", "call-after-legacy", "execute_sql", "{\"sql\":\"select 4\"}");
        assertNotEquals(legacy.getTaskId(), correlatedAfterLegacy.getTaskId());
        assertNotEquals(legacy.getRunId(), correlatedAfterLegacy.getRunId());
        var approvalScope = service.authorizeInvocation("session-1", "access-secret",
                "chat-after-legacy", "call-after-legacy");
        assertEquals(correlatedAfterLegacy.getTaskId(), approvalScope.getTaskId());
        assertEquals(correlatedAfterLegacy.getRunId(), approvalScope.getRunId());
        assertThrows(IllegalArgumentException.class, () -> service.authorizeToolCall(
                "session-1", "access-secret", "partial-chat", null, "execute_sql", "{}"));
    }

    @Test
    void deletesOnlyStoppedSessions() {
        MemoryStorage storage = new MemoryStorage();
        AgentConnectorSession session = session("session-1");
        storage.session = session;
        AgentConnectorServiceImpl service = service(storage);

        assertThrows(IllegalStateException.class, () -> service.deleteSession("session-1", null));
        session.setStatus(AgentConnectorSessionStatusEnum.REVOKED);
        service.deleteSession("session-1", null);

        assertNull(storage.session);
    }

    private static AgentConnectorServiceImpl service(IAgentConnectorStorage storage) {
        return new AgentConnectorServiceImpl(storage, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface Handler {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private static AgentConnectorSession session(String id) {
        AgentConnectorSession value = new AgentConnectorSession();
        value.setId(id);
        value.setStatus(AgentConnectorSessionStatusEnum.ACTIVE);
        value.setRefreshTokenHash("deadbeef");
        value.setRefreshTokenExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        value.setRevision(1L);
        return value;
    }

    private static AgentRun run(String taskId, String runId) {
        AgentRun run = new AgentRun();
        run.setId(runId); run.setTaskId(taskId); run.setStatus(AgentRunStatusEnum.QUEUED); run.setRevision(1L);
        return run;
    }

    private static final class MemoryStorage implements IAgentConnectorStorage {
        private AgentConnectorPairing pairing;
        private AgentConnectorSession session;
        private final List<AgentConnectorConversation> conversations = new ArrayList<>();
        private final List<AgentConnectorInvocation> invocations = new ArrayList<>();

        @Override public AgentConnectorPairing createPairing(AgentConnectorPairing value) { pairing = value; return value; }
        @Override public AgentConnectorPairing getPairing(String id) { return pairing; }
        @Override public List<AgentConnectorPairing> listPendingPairings() { return pairing == null ? List.of() : List.of(pairing); }
        @Override public AgentConnectorPairing updatePairing(AgentConnectorPairing value, long revision) { pairing = value; return value; }
        @Override public AgentConnectorSession createSession(AgentConnectorSession value) { session = value; return value; }
        @Override public AgentConnectorSession getSession(String id) { return session; }
        @Override public List<AgentConnectorSession> listSessions(Long ownerId) { return session == null ? List.of() : List.of(session); }
        @Override public List<AgentConnectorSession> listAllSessions() { return session == null ? List.of() : List.of(session); }
        @Override public List<AgentConnectorSession> listActiveSessionsBefore(Date cutoff, int limit) {
            return session != null && session.getStatus() == AgentConnectorSessionStatusEnum.ACTIVE
                    && session.getLastUsedAt() != null && session.getLastUsedAt().before(cutoff) ? List.of(session) : List.of();
        }
        @Override public AgentConnectorSession getSessionByTaskId(String taskId) {
            return session != null && java.util.Objects.equals(session.getTaskId(), taskId) ? session : null;
        }
        @Override public AgentConnectorSession updateSession(AgentConnectorSession value, long revision) { session = value; return value; }
        @Override public void deleteSession(String sessionId) {
            List<String> conversationIds = conversations.stream()
                    .filter(value -> java.util.Objects.equals(value.getConnectorSessionId(), sessionId))
                    .map(AgentConnectorConversation::getId).toList();
            invocations.removeIf(value -> conversationIds.contains(value.getConversationId()));
            conversations.removeIf(value -> java.util.Objects.equals(value.getConnectorSessionId(), sessionId));
            if (session != null && java.util.Objects.equals(session.getId(), sessionId)) session = null;
        }
        @Override public AgentConnectorConversation createConversation(AgentConnectorConversation value) {
            conversations.add(value); return value;
        }
        @Override public AgentConnectorConversation getConversation(String sessionId, String externalSessionId) {
            return conversations.stream().filter(value -> java.util.Objects.equals(value.getConnectorSessionId(), sessionId)
                    && java.util.Objects.equals(value.getExternalSessionId(), externalSessionId)).findFirst().orElse(null);
        }
        @Override public AgentConnectorConversation getConversationByTaskId(String taskId) {
            return conversations.stream().filter(value -> java.util.Objects.equals(value.getTaskId(), taskId))
                    .findFirst().orElse(null);
        }
        @Override public List<AgentConnectorConversation> listConversations(String sessionId) {
            return conversations.stream().filter(value -> java.util.Objects.equals(value.getConnectorSessionId(), sessionId)).toList();
        }
        @Override public AgentConnectorConversation updateConversation(AgentConnectorConversation value, long revision) {
            return value;
        }
        @Override public AgentConnectorInvocation createInvocation(AgentConnectorInvocation value) {
            invocations.add(value); return value;
        }
        @Override public AgentConnectorInvocation getInvocation(String conversationId, String externalCallId) {
            return invocations.stream().filter(value -> java.util.Objects.equals(value.getConversationId(), conversationId)
                    && java.util.Objects.equals(value.getExternalCallId(), externalCallId)).findFirst().orElse(null);
        }
        @Override public List<AgentConnectorInvocation> listInvocations(String conversationId) {
            return invocations.stream().filter(value -> java.util.Objects.equals(value.getConversationId(), conversationId)).toList();
        }
        @Override public AgentConnectorInvocation updateInvocation(AgentConnectorInvocation value, long revision) {
            return value;
        }
    }
}
