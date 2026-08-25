package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorPairingStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorSessionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorConversationStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorInvocationStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairing;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorSession;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorConversation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorInvocation;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class H2AgentConnectorStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsNullableOwnerAndRotatingSessionState() {
        H2AgentConnectorStorage storage = new H2AgentConnectorStorage(dataSource(tempDir.resolve("connector")));
        AgentConnectorPairing pairing = pairing();
        assertNull(storage.createPairing(pairing).getOwnerId());

        pairing.setOwnerId(7L);
        pairing.setStatus(AgentConnectorPairingStatusEnum.APPROVED);
        pairing.setRevision(2L);
        assertEquals(7L, storage.updatePairing(pairing, 1L).getOwnerId());

        AgentConnectorSession session = session();
        assertEquals("refresh-1", storage.createSession(session).getRefreshTokenHash());
        session.setRefreshTokenHash("refresh-2");
        session.setRevision(2L);
        assertEquals("refresh-2", storage.updateSession(session, 1L).getRefreshTokenHash());
        assertEquals(1, storage.listSessions(7L).size());
        assertEquals("session-1", storage.getSessionByTaskId("task-1").getId());
        assertEquals(1, storage.listAllSessions().size());
        assertEquals(1, storage.listActiveSessionsBefore(new Date(2_000L), 10).size());
    }

    @Test
    void persistsConversationAndIdempotentInvocationMappings() {
        H2AgentConnectorStorage storage = new H2AgentConnectorStorage(dataSource(tempDir.resolve("audit")));
        AgentConnectorSession session = session();
        session.setTaskId(null);
        session.setRunId(null);
        storage.createSession(session);

        AgentConnectorConversation conversation = new AgentConnectorConversation();
        conversation.setId("conversation-1"); conversation.setConnectorSessionId("session-1");
        conversation.setExternalSessionId("dsh-session-1"); conversation.setTaskId("task-conversation-1");
        conversation.setStatus(AgentConnectorConversationStatusEnum.ACTIVE);
        conversation.setCreatedAt(new Date(1_100L)); conversation.setLastUsedAt(new Date(1_200L));
        conversation.setRevision(1L);
        storage.createConversation(conversation);

        AgentConnectorInvocation invocation = new AgentConnectorInvocation();
        invocation.setId("invocation-1"); invocation.setConversationId("conversation-1");
        invocation.setExternalCallId("call-1"); invocation.setToolName("execute_sql");
        invocation.setTaskId("task-conversation-1"); invocation.setRunId("run-call-1");
        invocation.setStatus(AgentConnectorInvocationStatusEnum.RUNNING);
        invocation.setCreatedAt(new Date(1_300L)); invocation.setUpdatedAt(new Date(1_300L));
        invocation.setRevision(1L);
        storage.createInvocation(invocation);

        assertEquals("task-conversation-1", storage.getConversation("session-1", "dsh-session-1").getTaskId());
        assertEquals("session-1", storage.getSessionByTaskId("task-conversation-1").getId());
        assertEquals("run-call-1", storage.getInvocation("conversation-1", "call-1").getRunId());
        assertEquals(1, storage.listInvocations("conversation-1").size());

        conversation.setLastUsedAt(new Date(2_000L)); conversation.setRevision(2L);
        assertEquals(new Date(2_000L), storage.updateConversation(conversation, 1L).getLastUsedAt());
        invocation.setStatus(AgentConnectorInvocationStatusEnum.COMPLETED);
        invocation.setUpdatedAt(new Date(2_100L)); invocation.setCompletedAt(new Date(2_100L));
        invocation.setResponseJson("{\"content\":[]}"); invocation.setRevision(2L);
        assertEquals("{\"content\":[]}", storage.updateInvocation(invocation, 1L).getResponseJson());

        storage.deleteSession("session-1");
        assertNull(storage.getSession("session-1"));
        assertNull(storage.getConversation("session-1", "dsh-session-1"));
        assertNull(storage.getInvocation("conversation-1", "call-1"));
    }

    private static JdbcDataSource dataSource(Path path) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:file:" + path.toAbsolutePath() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    private static AgentConnectorPairing pairing() {
        AgentConnectorPairing value = new AgentConnectorPairing();
        value.setId("pairing-1"); value.setClientName("DSH"); value.setPollTokenHash("poll");
        value.setUserCode("ABCD-EFGH"); value.setStatus(AgentConnectorPairingStatusEnum.PENDING);
        value.setExpiresAt(new Date(10_000L)); value.setCreatedAt(new Date(1_000L)); value.setRevision(1L);
        return value;
    }

    private static AgentConnectorSession session() {
        AgentConnectorSession value = new AgentConnectorSession();
        value.setId("session-1"); value.setClientName("DSH"); value.setAgentId("agent-1");
        value.setAgentName("Agent"); value.setOwnerId(7L); value.setTaskId("task-1"); value.setRunId("run-1");
        value.setStatus(AgentConnectorSessionStatusEnum.ACTIVE); value.setAccessTokenHash("access-1");
        value.setRefreshTokenHash("refresh-1"); value.setAccessTokenExpiresAt(new Date(20_000L));
        value.setRefreshTokenExpiresAt(new Date(30_000L)); value.setCreatedAt(new Date(1_000L));
        value.setLastUsedAt(new Date(1_000L)); value.setRevision(1L);
        return value;
    }
}
