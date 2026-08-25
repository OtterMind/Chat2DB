package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorPairingStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorSessionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorConversationStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentConnectorInvocationStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorConversation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorInvocation;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorPairing;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorSession;
import ai.chat2db.community.domain.api.service.storage.IAgentConnectorStorage;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;

@Component
public class H2AgentConnectorStorage implements IAgentConnectorStorage {
    private static final String MIGRATION_LOCATION = "classpath:db/connector/migration";
    private final DataSource dataSource;

    public H2AgentConnectorStorage() {
        this(defaultDataSource());
    }

    H2AgentConnectorStorage(DataSource dataSource) {
        this.dataSource = dataSource;
        Flyway.configure(H2AgentConnectorStorage.class.getClassLoader()).dataSource(dataSource)
                .locations(MIGRATION_LOCATION).load().migrate();
    }

    @Override
    public AgentConnectorPairing createPairing(AgentConnectorPairing value) {
        String sql = "INSERT INTO agent_connector_pairing (id,client_name,poll_token_hash,user_code,status,"
                + "agent_id,agent_name,owner_id,exchange_code,session_id,expires_at,created_at,decided_at,revision) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPairing(statement, value);
            statement.executeUpdate();
            return getPairing(value.getId());
        } catch (SQLException exception) {
            throw failure("create pairing", exception);
        }
    }

    @Override
    public AgentConnectorPairing getPairing(String id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_connector_pairing WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readPairing(results) : null;
            }
        } catch (SQLException exception) {
            throw failure("get pairing", exception);
        }
    }

    @Override
    public List<AgentConnectorPairing> listPendingPairings() {
        List<AgentConnectorPairing> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_connector_pairing WHERE status='PENDING' ORDER BY created_at");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) values.add(readPairing(results));
            return values;
        } catch (SQLException exception) {
            throw failure("list pending pairings", exception);
        }
    }

    @Override
    public AgentConnectorPairing updatePairing(AgentConnectorPairing value, long expectedRevision) {
        String sql = "UPDATE agent_connector_pairing SET client_name=?,poll_token_hash=?,user_code=?,status=?,"
                + "agent_id=?,agent_name=?,owner_id=?,exchange_code=?,session_id=?,expires_at=?,created_at=?,"
                + "decided_at=?,revision=? WHERE id=? AND revision=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPairingUpdate(statement, value);
            statement.setString(14, value.getId());
            statement.setLong(15, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ConcurrentModificationException("pairing revision changed");
            return getPairing(value.getId());
        } catch (SQLException exception) {
            throw failure("update pairing", exception);
        }
    }

    @Override
    public AgentConnectorSession createSession(AgentConnectorSession value) {
        String sql = "INSERT INTO agent_connector_session (id,client_name,agent_id,agent_name,owner_id,task_id,run_id,"
                + "status,access_token_hash,refresh_token_hash,access_expires_at,refresh_expires_at,created_at,last_used_at,"
                + "revoked_at,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindSession(statement, value);
            statement.executeUpdate();
            return getSession(value.getId());
        } catch (SQLException exception) {
            throw failure("create session", exception);
        }
    }

    @Override
    public AgentConnectorSession getSession(String id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_connector_session WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readSession(results) : null;
            }
        } catch (SQLException exception) {
            throw failure("get session", exception);
        }
    }

    @Override
    public List<AgentConnectorSession> listSessions(Long ownerId) {
        List<AgentConnectorSession> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_connector_session WHERE owner_id=? ORDER BY created_at DESC")) {
            if (ownerId == null) statement.setNull(1, Types.BIGINT); else statement.setLong(1, ownerId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(readSession(results));
            }
            return values;
        } catch (SQLException exception) {
            throw failure("list sessions", exception);
        }
    }

    @Override
    public List<AgentConnectorSession> listAllSessions() {
        return querySessions("SELECT * FROM agent_connector_session ORDER BY created_at DESC", null, 0);
    }

    @Override
    public List<AgentConnectorSession> listActiveSessionsBefore(Date lastUsedBefore, int limit) {
        if (lastUsedBefore == null || limit <= 0) return List.of();
        return querySessions("SELECT * FROM agent_connector_session WHERE status='ACTIVE' "
                + "AND (last_used_at IS NULL OR last_used_at<?) "
                + "ORDER BY last_used_at LIMIT ?", lastUsedBefore, limit);
    }

    @Override
    public AgentConnectorSession getSessionByTaskId(String taskId) {
        if (taskId == null) return null;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_connector_session WHERE task_id=? ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, taskId);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) return readSession(results);
            }
        } catch (SQLException exception) {
            throw failure("get session by task", exception);
        }
        AgentConnectorConversation conversation = getConversationByTaskId(taskId);
        return conversation == null ? null : getSession(conversation.getConnectorSessionId());
    }

    @Override
    public AgentConnectorConversation createConversation(AgentConnectorConversation value) {
        String sql = "INSERT INTO agent_connector_conversation (id,connector_session_id,external_session_id,task_id,"
                + "status,created_at,last_used_at,closed_at,revision) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindConversation(statement, value);
            statement.executeUpdate();
            return getConversation(value.getConnectorSessionId(), value.getExternalSessionId());
        } catch (SQLException exception) {
            throw failure("create connector conversation", exception);
        }
    }

    @Override
    public AgentConnectorConversation getConversation(String connectorSessionId, String externalSessionId) {
        return queryConversation("SELECT * FROM agent_connector_conversation WHERE connector_session_id=? AND external_session_id=?",
                statement -> { statement.setString(1, connectorSessionId); statement.setString(2, externalSessionId); });
    }

    @Override
    public AgentConnectorConversation getConversationByTaskId(String taskId) {
        if (taskId == null) return null;
        return queryConversation("SELECT * FROM agent_connector_conversation WHERE task_id=?",
                statement -> statement.setString(1, taskId));
    }

    @Override
    public List<AgentConnectorConversation> listConversations(String connectorSessionId) {
        List<AgentConnectorConversation> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_connector_conversation WHERE connector_session_id=? ORDER BY last_used_at DESC")) {
            statement.setString(1, connectorSessionId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(readConversation(results));
            }
            return values;
        } catch (SQLException exception) {
            throw failure("list connector conversations", exception);
        }
    }

    @Override
    public AgentConnectorConversation updateConversation(AgentConnectorConversation value, long expectedRevision) {
        String sql = "UPDATE agent_connector_conversation SET status=?,last_used_at=?,closed_at=?,revision=? "
                + "WHERE id=? AND revision=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value.getStatus().name());
            statement.setLong(2, value.getLastUsedAt().getTime());
            nullableDate(statement, 3, value.getClosedAt());
            statement.setLong(4, value.getRevision());
            statement.setString(5, value.getId());
            statement.setLong(6, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ConcurrentModificationException("conversation revision changed");
            return getConversation(value.getConnectorSessionId(), value.getExternalSessionId());
        } catch (SQLException exception) {
            throw failure("update connector conversation", exception);
        }
    }

    @Override
    public AgentConnectorInvocation createInvocation(AgentConnectorInvocation value) {
        String sql = "INSERT INTO agent_connector_invocation (id,conversation_id,external_call_id,tool_name,task_id,run_id,"
                + "status,created_at,updated_at,completed_at,response_json,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindInvocation(statement, value);
            statement.executeUpdate();
            return getInvocation(value.getConversationId(), value.getExternalCallId());
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                AgentConnectorInvocation existing = getInvocation(value.getConversationId(), value.getExternalCallId());
                if (existing != null) return existing;
            }
            throw failure("create connector invocation", exception);
        }
    }

    @Override
    public AgentConnectorInvocation getInvocation(String conversationId, String externalCallId) {
        return queryInvocation("SELECT * FROM agent_connector_invocation WHERE conversation_id=? AND external_call_id=?",
                statement -> { statement.setString(1, conversationId); statement.setString(2, externalCallId); });
    }

    @Override
    public List<AgentConnectorInvocation> listInvocations(String conversationId) {
        List<AgentConnectorInvocation> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_connector_invocation WHERE conversation_id=? ORDER BY created_at ASC")) {
            statement.setString(1, conversationId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(readInvocation(results));
            }
            return values;
        } catch (SQLException exception) {
            throw failure("list connector invocations", exception);
        }
    }

    @Override
    public AgentConnectorInvocation updateInvocation(AgentConnectorInvocation value, long expectedRevision) {
        String sql = "UPDATE agent_connector_invocation SET status=?,updated_at=?,completed_at=?,response_json=?,revision=? "
                + "WHERE id=? AND revision=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value.getStatus().name());
            statement.setLong(2, value.getUpdatedAt().getTime());
            nullableDate(statement, 3, value.getCompletedAt());
            statement.setString(4, value.getResponseJson());
            statement.setLong(5, value.getRevision());
            statement.setString(6, value.getId());
            statement.setLong(7, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ConcurrentModificationException("invocation revision changed");
            return getInvocation(value.getConversationId(), value.getExternalCallId());
        } catch (SQLException exception) {
            throw failure("update connector invocation", exception);
        }
    }

    private List<AgentConnectorSession> querySessions(String sql, Date cutoff, int limit) {
        List<AgentConnectorSession> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (cutoff != null) {
                statement.setLong(1, cutoff.getTime());
                statement.setInt(2, limit);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(readSession(results));
            }
            return values;
        } catch (SQLException exception) {
            throw failure("list sessions", exception);
        }
    }

    @Override
    public AgentConnectorSession updateSession(AgentConnectorSession value, long expectedRevision) {
        String sql = "UPDATE agent_connector_session SET client_name=?,agent_id=?,agent_name=?,owner_id=?,task_id=?,run_id=?,"
                + "status=?,access_token_hash=?,refresh_token_hash=?,access_expires_at=?,refresh_expires_at=?,created_at=?,"
                + "last_used_at=?,revoked_at=?,revision=? WHERE id=? AND revision=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindSessionUpdate(statement, value);
            statement.setString(16, value.getId());
            statement.setLong(17, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ConcurrentModificationException("session revision changed");
            return getSession(value.getId());
        } catch (SQLException exception) {
            throw failure("update session", exception);
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                executeDelete(connection, "DELETE FROM agent_connector_pairing WHERE session_id=?", sessionId);
                executeDelete(connection, "DELETE FROM agent_connector_invocation WHERE conversation_id IN "
                        + "(SELECT id FROM agent_connector_conversation WHERE connector_session_id=?)", sessionId);
                executeDelete(connection, "DELETE FROM agent_connector_conversation WHERE connector_session_id=?", sessionId);
                int deleted = executeDelete(connection, "DELETE FROM agent_connector_session WHERE id=?", sessionId);
                if (deleted != 1) throw new NoSuchElementException("Connector Session not found");
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("delete session", exception);
        }
    }

    private static int executeDelete(Connection connection, String sql, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            return statement.executeUpdate();
        }
    }

    private static void bindPairing(PreparedStatement s, AgentConnectorPairing v) throws SQLException {
        s.setString(1, v.getId());
        s.setString(2, v.getClientName());
        s.setString(3, v.getPollTokenHash());
        s.setString(4, v.getUserCode());
        s.setString(5, v.getStatus().name());
        nullable(s, 6, v.getAgentId()); nullable(s, 7, v.getAgentName()); nullableLong(s, 8, v.getOwnerId());
        nullable(s, 9, v.getExchangeCode()); nullable(s, 10, v.getSessionId());
        s.setLong(11, v.getExpiresAt().getTime()); s.setLong(12, v.getCreatedAt().getTime());
        nullableDate(s, 13, v.getDecidedAt()); s.setLong(14, v.getRevision());
    }

    private static void bindPairingUpdate(PreparedStatement s, AgentConnectorPairing v) throws SQLException {
        s.setString(1, v.getClientName()); s.setString(2, v.getPollTokenHash()); s.setString(3, v.getUserCode());
        s.setString(4, v.getStatus().name()); nullable(s, 5, v.getAgentId()); nullable(s, 6, v.getAgentName());
        nullableLong(s, 7, v.getOwnerId()); nullable(s, 8, v.getExchangeCode()); nullable(s, 9, v.getSessionId());
        s.setLong(10, v.getExpiresAt().getTime()); s.setLong(11, v.getCreatedAt().getTime());
        nullableDate(s, 12, v.getDecidedAt()); s.setLong(13, v.getRevision());
    }

    private static void bindSession(PreparedStatement s, AgentConnectorSession v) throws SQLException {
        s.setString(1, v.getId()); bindSessionUpdate(s, v, 2);
    }

    private static void bindSessionUpdate(PreparedStatement s, AgentConnectorSession v) throws SQLException {
        bindSessionUpdate(s, v, 1);
    }

    private static void bindSessionUpdate(PreparedStatement s, AgentConnectorSession v, int i) throws SQLException {
        s.setString(i++, v.getClientName()); s.setString(i++, v.getAgentId()); s.setString(i++, v.getAgentName());
        nullableLong(s, i++, v.getOwnerId()); nullable(s, i++, v.getTaskId()); nullable(s, i++, v.getRunId());
        s.setString(i++, v.getStatus().name()); s.setString(i++, v.getAccessTokenHash()); s.setString(i++, v.getRefreshTokenHash());
        s.setLong(i++, v.getAccessTokenExpiresAt().getTime()); s.setLong(i++, v.getRefreshTokenExpiresAt().getTime());
        s.setLong(i++, v.getCreatedAt().getTime()); s.setLong(i++, v.getLastUsedAt().getTime());
        nullableDate(s, i++, v.getRevokedAt()); s.setLong(i, v.getRevision());
    }

    private static AgentConnectorPairing readPairing(ResultSet r) throws SQLException {
        AgentConnectorPairing v = new AgentConnectorPairing();
        v.setId(r.getString("id")); v.setClientName(r.getString("client_name"));
        v.setPollTokenHash(r.getString("poll_token_hash")); v.setUserCode(r.getString("user_code"));
        v.setStatus(AgentConnectorPairingStatusEnum.valueOf(r.getString("status")));
        v.setAgentId(r.getString("agent_id")); v.setAgentName(r.getString("agent_name"));
        long owner = r.getLong("owner_id"); v.setOwnerId(r.wasNull() ? null : owner);
        v.setExchangeCode(r.getString("exchange_code")); v.setSessionId(r.getString("session_id"));
        v.setExpiresAt(new Date(r.getLong("expires_at"))); v.setCreatedAt(new Date(r.getLong("created_at")));
        long decided = r.getLong("decided_at"); v.setDecidedAt(r.wasNull() ? null : new Date(decided));
        v.setRevision(r.getLong("revision")); return v;
    }

    private static AgentConnectorSession readSession(ResultSet r) throws SQLException {
        AgentConnectorSession v = new AgentConnectorSession();
        v.setId(r.getString("id")); v.setClientName(r.getString("client_name")); v.setAgentId(r.getString("agent_id"));
        v.setAgentName(r.getString("agent_name")); long owner = r.getLong("owner_id"); v.setOwnerId(r.wasNull() ? null : owner);
        v.setTaskId(r.getString("task_id")); v.setRunId(r.getString("run_id"));
        v.setStatus(AgentConnectorSessionStatusEnum.valueOf(r.getString("status")));
        v.setAccessTokenHash(r.getString("access_token_hash")); v.setRefreshTokenHash(r.getString("refresh_token_hash"));
        v.setAccessTokenExpiresAt(new Date(r.getLong("access_expires_at")));
        v.setRefreshTokenExpiresAt(new Date(r.getLong("refresh_expires_at")));
        v.setCreatedAt(new Date(r.getLong("created_at"))); v.setLastUsedAt(new Date(r.getLong("last_used_at")));
        long revoked = r.getLong("revoked_at"); v.setRevokedAt(r.wasNull() ? null : new Date(revoked));
        v.setRevision(r.getLong("revision")); return v;
    }

    private AgentConnectorConversation queryConversation(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readConversation(results) : null;
            }
        } catch (SQLException exception) {
            throw failure("query connector conversation", exception);
        }
    }

    private AgentConnectorInvocation queryInvocation(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readInvocation(results) : null;
            }
        } catch (SQLException exception) {
            throw failure("query connector invocation", exception);
        }
    }

    private static void bindConversation(PreparedStatement s, AgentConnectorConversation v) throws SQLException {
        s.setString(1, v.getId()); s.setString(2, v.getConnectorSessionId()); s.setString(3, v.getExternalSessionId());
        s.setString(4, v.getTaskId()); s.setString(5, v.getStatus().name()); s.setLong(6, v.getCreatedAt().getTime());
        s.setLong(7, v.getLastUsedAt().getTime()); nullableDate(s, 8, v.getClosedAt()); s.setLong(9, v.getRevision());
    }

    private static AgentConnectorConversation readConversation(ResultSet r) throws SQLException {
        AgentConnectorConversation v = new AgentConnectorConversation();
        v.setId(r.getString("id")); v.setConnectorSessionId(r.getString("connector_session_id"));
        v.setExternalSessionId(r.getString("external_session_id")); v.setTaskId(r.getString("task_id"));
        v.setStatus(AgentConnectorConversationStatusEnum.valueOf(r.getString("status")));
        v.setCreatedAt(new Date(r.getLong("created_at"))); v.setLastUsedAt(new Date(r.getLong("last_used_at")));
        long closed = r.getLong("closed_at"); v.setClosedAt(r.wasNull() ? null : new Date(closed));
        v.setRevision(r.getLong("revision")); return v;
    }

    private static void bindInvocation(PreparedStatement s, AgentConnectorInvocation v) throws SQLException {
        s.setString(1, v.getId()); s.setString(2, v.getConversationId()); s.setString(3, v.getExternalCallId());
        s.setString(4, v.getToolName()); s.setString(5, v.getTaskId()); s.setString(6, v.getRunId());
        s.setString(7, v.getStatus().name()); s.setLong(8, v.getCreatedAt().getTime());
        s.setLong(9, v.getUpdatedAt().getTime()); nullableDate(s, 10, v.getCompletedAt());
        s.setString(11, v.getResponseJson()); s.setLong(12, v.getRevision());
    }

    private static AgentConnectorInvocation readInvocation(ResultSet r) throws SQLException {
        AgentConnectorInvocation v = new AgentConnectorInvocation();
        v.setId(r.getString("id")); v.setConversationId(r.getString("conversation_id"));
        v.setExternalCallId(r.getString("external_call_id")); v.setToolName(r.getString("tool_name"));
        v.setTaskId(r.getString("task_id")); v.setRunId(r.getString("run_id"));
        v.setStatus(AgentConnectorInvocationStatusEnum.valueOf(r.getString("status")));
        v.setCreatedAt(new Date(r.getLong("created_at"))); v.setUpdatedAt(new Date(r.getLong("updated_at")));
        long completed = r.getLong("completed_at"); v.setCompletedAt(r.wasNull() ? null : new Date(completed));
        v.setResponseJson(r.getString("response_json"));
        v.setRevision(r.getLong("revision")); return v;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static void nullable(PreparedStatement s, int index, Object value) throws SQLException {
        if (value == null) s.setNull(index, Types.VARCHAR);
        else s.setString(index, value.toString());
    }

    private static void nullableLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) s.setNull(index, Types.BIGINT); else s.setLong(index, value);
    }

    private static void nullableDate(PreparedStatement s, int index, Date value) throws SQLException {
        if (value == null) s.setNull(index, Types.BIGINT); else s.setLong(index, value.getTime());
    }

    private static DataSource defaultDataSource() {
        try {
            Path database = Path.of(ConfigUtils.getEnvBasePath(), "storage", "connector", "chat2db-connector")
                    .toAbsolutePath().normalize();
            Files.createDirectories(database.getParent());
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1");
            source.setUser("sa"); source.setPassword(""); return source;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize Agent Connector database", exception);
        }
    }

    private static IllegalStateException failure(String action, SQLException exception) {
        return new IllegalStateException("failed to " + action + " in Agent Connector store", exception);
    }
}
