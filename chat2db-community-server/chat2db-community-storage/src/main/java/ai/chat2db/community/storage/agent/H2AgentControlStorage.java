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
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRiskLevelEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlOperationClassEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlProposalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskLinkStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleCatchUpPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleConcurrencyPolicyEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionSourceEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleExecutionStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleReasonCodeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTransportEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentDeliveryStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentGatewayPlatformEnum;
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
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleClaim;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.agent.AgentDeliveryCommand;
import ai.chat2db.community.domain.api.model.agent.AgentExternalConversationBinding;
import ai.chat2db.community.domain.api.model.agent.AgentGatewayChannel;
import ai.chat2db.community.domain.api.model.agent.AgentInboundMessage;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentGatewayStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import ai.chat2db.community.domain.api.service.storage.IAgentTaskScheduleStorage;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class H2AgentControlStorage implements IAgentControlStorage, IAgentRuntimeControlStorage,
        IAgentGatewayStorage, IAgentTaskScheduleStorage {

    private static final String MIGRATION_LOCATION = "classpath:db/agent/migration";
    private static final String DATABASE_NAME = "chat2db-agent";

    private final DataSource dataSource;

    public H2AgentControlStorage() {
        this(defaultDatabasePath());
    }

    public H2AgentControlStorage(Path databasePath) {
        this(createDataSource(databasePath));
    }

    H2AgentControlStorage(DataSource dataSource) {
        this.dataSource = dataSource;
        Flyway.configure(H2AgentControlStorage.class.getClassLoader())
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();
    }

    @Override
    public AgentDefinition createAgent(AgentDefinition agent) {
        String sql = """
                INSERT INTO agent_definition (
                    id, name, avatar, description, status, runtime_type,
                    runtime_profile_id, model_config_id, system_prompt,
                    capabilities_json, data_scopes_json, output_contract,
                    created_by, created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindAgent(statement, agent);
            statement.executeUpdate();
            return getAgent(agent.getId());
        } catch (SQLException exception) {
            throw storageFailure("create agent", exception);
        }
    }

    @Override
    public AgentDefinition updateAgent(AgentDefinition agent, long expectedRevision) {
        String sql = """
                UPDATE agent_definition SET name = ?, avatar = ?, description = ?, status = ?, runtime_type = ?,
                    runtime_profile_id = ?, model_config_id = ?, system_prompt = ?, capabilities_json = ?,
                    data_scopes_json = ?, output_contract = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, agent.getName());
            statement.setString(index++, agent.getAvatar());
            statement.setString(index++, agent.getDescription());
            statement.setString(index++, agent.getStatus().name());
            statement.setString(index++, agent.getRuntimeType().name());
            statement.setString(index++, agent.getRuntimeProfileId());
            statement.setString(index++, agent.getModelConfigId());
            statement.setString(index++, agent.getSystemPrompt());
            statement.setString(index++, JSON.toJSONString(agent.getCapabilities()));
            statement.setString(index++, JSON.toJSONString(agent.getDataScopes()));
            statement.setString(index++, agent.getOutputContract());
            statement.setLong(index++, agent.getGmtModified().getTime());
            statement.setLong(index++, agent.getRevision());
            statement.setString(index++, agent.getId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("agent revision has changed: " + agent.getId());
            }
            return getAgent(agent.getId());
        } catch (SQLException exception) {
            throw storageFailure("update agent", exception);
        }
    }

    @Override
    public AgentDefinition getAgent(String id) {
        String sql = "SELECT * FROM agent_definition WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readAgent(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get agent", exception);
        }
    }

    @Override
    public List<AgentDefinition> listAgents() {
        String sql = "SELECT * FROM agent_definition ORDER BY updated_at DESC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<AgentDefinition> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(readAgent(resultSet));
            }
            return result;
        } catch (SQLException exception) {
            throw storageFailure("list agents", exception);
        }
    }

    @Override
    public AgentRuntimeProfile createRuntimeProfile(AgentRuntimeProfile profile) {
        String sql = """
                INSERT INTO agent_runtime_profile (
                    id, name, transport, provider, executable, model, working_directory_policy,
                    custom_arguments_json, environment_references_json, mcp_configuration,
                    timeout_seconds, max_concurrency, thinking_mode, service_tier,
                    session_resume_enabled, approval_bridge_enabled, enabled, created_by,
                    created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRuntimeProfile(statement, profile);
            statement.executeUpdate();
            return getRuntimeProfile(profile.getId());
        } catch (SQLException exception) {
            throw storageFailure("create runtime profile", exception);
        }
    }

    @Override
    public AgentRuntimeProfile updateRuntimeProfile(AgentRuntimeProfile profile, long expectedRevision) {
        String sql = """
                UPDATE agent_runtime_profile SET name = ?, transport = ?, provider = ?, executable = ?,
                    model = ?, working_directory_policy = ?, custom_arguments_json = ?,
                    environment_references_json = ?, mcp_configuration = ?, timeout_seconds = ?,
                    max_concurrency = ?, thinking_mode = ?, service_tier = ?, session_resume_enabled = ?,
                    approval_bridge_enabled = ?, enabled = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, profile.getName());
            statement.setString(index++, profile.getTransport().name());
            statement.setString(index++, profile.getProvider().name());
            statement.setString(index++, profile.getExecutable());
            statement.setString(index++, profile.getModel());
            statement.setString(index++, profile.getWorkingDirectoryPolicy());
            statement.setString(index++, JSON.toJSONString(profile.getCustomArguments()));
            statement.setString(index++, JSON.toJSONString(profile.getEnvironmentReferences()));
            statement.setString(index++, profile.getMcpConfiguration());
            statement.setInt(index++, profile.getTimeoutSeconds());
            statement.setInt(index++, profile.getMaxConcurrency());
            statement.setString(index++, profile.getThinkingMode());
            statement.setString(index++, profile.getServiceTier());
            statement.setBoolean(index++, Boolean.TRUE.equals(profile.getSessionResumeEnabled()));
            statement.setBoolean(index++, Boolean.TRUE.equals(profile.getApprovalBridgeEnabled()));
            statement.setBoolean(index++, Boolean.TRUE.equals(profile.getEnabled()));
            statement.setLong(index++, profile.getGmtModified().getTime());
            statement.setLong(index++, profile.getRevision());
            statement.setString(index++, profile.getId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("runtime profile revision has changed: " + profile.getId());
            }
            return getRuntimeProfile(profile.getId());
        } catch (SQLException exception) {
            throw storageFailure("update runtime profile", exception);
        }
    }

    @Override
    public AgentRuntimeProfile getRuntimeProfile(String id) {
        return queryRuntimeProfile("SELECT * FROM agent_runtime_profile WHERE id = ?", statement ->
                statement.setString(1, id));
    }

    @Override
    public List<AgentRuntimeProfile> listRuntimeProfiles() {
        String sql = "SELECT * FROM agent_runtime_profile ORDER BY updated_at DESC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<AgentRuntimeProfile> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(readRuntimeProfile(resultSet));
            }
            return result;
        } catch (SQLException exception) {
            throw storageFailure("list runtime profiles", exception);
        }
    }

    @Override
    public AgentRuntimeInstance createRuntimeInstance(AgentRuntimeInstance instance) {
        String sql = """
                INSERT INTO agent_runtime_instance (
                    id, daemon_id, provider, provider_version, protocol_version, capabilities_json,
                    max_concurrency, active_runs, status, last_heartbeat_at, registered_at,
                    updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRuntimeInstance(statement, instance);
            statement.executeUpdate();
            return getRuntimeInstance(instance.getId());
        } catch (SQLException exception) {
            throw storageFailure("create runtime instance", exception);
        }
    }

    @Override
    public AgentRuntimeInstance updateRuntimeInstance(AgentRuntimeInstance instance, long expectedRevision) {
        String sql = """
                UPDATE agent_runtime_instance SET provider_version = ?, protocol_version = ?,
                    capabilities_json = ?, max_concurrency = ?, active_runs = ?, status = ?,
                    last_heartbeat_at = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, instance.getProviderVersion());
            statement.setString(index++, instance.getProtocolVersion());
            statement.setString(index++, JSON.toJSONString(instance.getCapabilities()));
            statement.setInt(index++, instance.getMaxConcurrency());
            statement.setInt(index++, instance.getActiveRuns());
            statement.setString(index++, instance.getStatus().name());
            statement.setLong(index++, instance.getLastHeartbeatAt().getTime());
            statement.setLong(index++, instance.getGmtModified().getTime());
            statement.setLong(index++, instance.getRevision());
            statement.setString(index++, instance.getId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("runtime instance revision has changed: " + instance.getId());
            }
            return getRuntimeInstance(instance.getId());
        } catch (SQLException exception) {
            throw storageFailure("update runtime instance", exception);
        }
    }

    @Override
    public AgentRuntimeInstance heartbeatRuntimeInstance(String instanceId, String daemonId,
                                                          AgentRuntimeInstanceStatusEnum status,
                                                          Date heartbeatAt) {
        String sql = """
                UPDATE agent_runtime_instance
                SET status = ?, last_heartbeat_at = ?, updated_at = ?, revision = revision + 1
                WHERE id = ? AND daemon_id = ? AND status <> 'DISABLED'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setLong(2, heartbeatAt.getTime());
            statement.setLong(3, heartbeatAt.getTime());
            statement.setString(4, instanceId);
            statement.setString(5, daemonId);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException(
                        "runtime instance is unavailable for heartbeat: " + instanceId);
            }
            return getRuntimeInstance(instanceId);
        } catch (SQLException exception) {
            throw storageFailure("heartbeat runtime instance", exception);
        }
    }

    @Override
    public AgentRuntimeInstance getRuntimeInstance(String id) {
        return queryRuntimeInstance("SELECT * FROM agent_runtime_instance WHERE id = ?", statement ->
                statement.setString(1, id));
    }

    @Override
    public AgentRuntimeInstance findRuntimeInstance(String daemonId, AgentRuntimeProviderEnum provider) {
        return queryRuntimeInstance("SELECT * FROM agent_runtime_instance WHERE daemon_id = ? AND provider = ?",
                statement -> {
                    statement.setString(1, daemonId);
                    statement.setString(2, provider.name());
                });
    }

    @Override
    public List<AgentRuntimeInstance> listRuntimeInstances() {
        String sql = "SELECT * FROM agent_runtime_instance ORDER BY updated_at DESC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<AgentRuntimeInstance> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(readRuntimeInstance(resultSet));
            }
            return result;
        } catch (SQLException exception) {
            throw storageFailure("list runtime instances", exception);
        }
    }

    @Override
    public AgentRuntimeRunLease claimRuntimeRun(String instanceId, AgentRuntimeProviderEnum provider,
                                                String leaseTokenHash, String taskTokenHash,
                                                Date claimedAt, Date leaseExpiresAt) {
        String candidatesSql = """
                SELECT r.id, r.revision
                FROM agent_run r
                JOIN agent_runtime_profile p ON p.id = r.runtime_profile_id
                WHERE r.runtime_type = 'EXTERNAL_AGENT'
                  AND r.status = 'QUEUED'
                  AND p.transport = 'EXTERNAL_DAEMON'
                  AND r.runtime_provider = ?
                  AND p.enabled = TRUE
                ORDER BY r.created_at ASC, r.id ASC
                LIMIT 32
                """;
        String claimSql = """
                UPDATE agent_run
                SET status = 'DISPATCHED', updated_at = ?, revision = revision + 1
                WHERE id = ? AND status = 'QUEUED' AND revision = ?
                """;
        String reserveSlotSql = """
                UPDATE agent_runtime_instance
                SET active_runs = active_runs + 1, updated_at = ?, revision = revision + 1
                WHERE id = ? AND provider = ? AND status IN ('ONLINE', 'DEGRADED')
                  AND active_runs < max_concurrency
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<RunRevision> candidates = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(candidatesSql)) {
                    statement.setString(1, provider.name());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            candidates.add(new RunRevision(resultSet.getString("id"), resultSet.getLong("revision")));
                        }
                    }
                }
                for (RunRevision candidate : candidates) {
                    try (PreparedStatement statement = connection.prepareStatement(claimSql)) {
                        statement.setLong(1, claimedAt.getTime());
                        statement.setString(2, candidate.runId());
                        statement.setLong(3, candidate.revision());
                        if (statement.executeUpdate() != 1) {
                            continue;
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement(reserveSlotSql)) {
                        statement.setLong(1, claimedAt.getTime());
                        statement.setString(2, instanceId);
                        statement.setString(3, provider.name());
                        if (statement.executeUpdate() != 1) {
                            throw new ConcurrentModificationException(
                                    "runtime instance has no available execution slot: " + instanceId);
                        }
                    }
                    AgentRuntimeRunLease previous = queryRuntimeRunLease(connection, candidate.runId());
                    AgentRuntimeRunLease lease = new AgentRuntimeRunLease();
                    lease.setRunId(candidate.runId());
                    lease.setRuntimeInstanceId(instanceId);
                    lease.setLeaseAttempt(previous == null ? 1 : previous.getLeaseAttempt() + 1);
                    lease.setLeaseTokenHash(leaseTokenHash);
                    lease.setTaskTokenHash(taskTokenHash);
                    lease.setClaimedAt(claimedAt);
                    lease.setLeaseExpiresAt(leaseExpiresAt);
                    lease.setLastRenewedAt(claimedAt);
                    lease.setLastEventSequence(0L);
                    lease.setState(AgentRuntimeLeaseStateEnum.ACTIVE);
                    lease.setRevision(previous == null ? 1L : previous.getRevision() + 1);
                    if (previous == null) {
                        insertRuntimeRunLease(connection, lease);
                    } else {
                        replaceRuntimeRunLease(connection, lease, previous.getRevision());
                    }
                    connection.commit();
                    return getRuntimeRunLease(candidate.runId());
                }
                connection.commit();
                return null;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("claim runtime run", exception);
        }
    }

    @Override
    public AgentRuntimeRunLease getRuntimeRunLease(String runId) {
        try (Connection connection = dataSource.getConnection()) {
            return queryRuntimeRunLease(connection, runId);
        } catch (SQLException exception) {
            throw storageFailure("get runtime run lease", exception);
        }
    }

    @Override
    public AgentRuntimeRunLease updateRuntimeRunLease(AgentRuntimeRunLease lease, long expectedRevision) {
        try (Connection connection = dataSource.getConnection()) {
            replaceRuntimeRunLease(connection, lease, expectedRevision);
            return getRuntimeRunLease(lease.getRunId());
        } catch (SQLException exception) {
            throw storageFailure("update runtime run lease", exception);
        }
    }

    @Override
    public AgentRuntimeRunLease startRuntimeRun(AgentRuntimeRunLease lease, long expectedLeaseRevision,
                                                long expectedRunRevision) {
        String runSql = """
                UPDATE agent_run
                SET status = 'RUNNING', started_at = COALESCE(started_at, ?), updated_at = ?, revision = revision + 1
                WHERE id = ? AND status = 'DISPATCHED' AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                replaceRuntimeRunLease(connection, lease, expectedLeaseRevision);
                try (PreparedStatement statement = connection.prepareStatement(runSql)) {
                    statement.setLong(1, lease.getStartedAt().getTime());
                    statement.setLong(2, lease.getStartedAt().getTime());
                    statement.setString(3, lease.getRunId());
                    statement.setLong(4, expectedRunRevision);
                    if (statement.executeUpdate() != 1) {
                        throw new ConcurrentModificationException(
                                "runtime run is no longer dispatchable: " + lease.getRunId());
                    }
                }
                connection.commit();
                return getRuntimeRunLease(lease.getRunId());
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("start runtime run", exception);
        }
    }

    @Override
    public AgentRunEvent appendRuntimeRunEvent(AgentRunEvent event, int leaseAttempt,
                                               long runtimeSequence, Date acceptedAt,
                                               String providerSessionId) {
        String advanceSql = """
                UPDATE agent_runtime_run_lease
                SET last_event_sequence = ?, revision = revision + 1
                WHERE run_id = ? AND lease_attempt = ? AND last_event_sequence = ?
                  AND lease_expires_at >= ?
                """;
        String insertSql = """
                INSERT INTO agent_run_event (
                    event_id, run_id, event_type, content, payload_json,
                    occurred_at, persisted_at, runtime_attempt, runtime_sequence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String sessionSql = """
                UPDATE agent_run
                SET provider_session_id = ?, updated_at = ?, revision = revision + 1
                WHERE id = ? AND status IN ('RUNNING', 'WAITING_APPROVAL')
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentRunEvent existing = queryRunEvent(connection, event.getEventId());
                if (existing != null) {
                    requireMatchingRuntimeEvent(existing, event, leaseAttempt, runtimeSequence);
                    connection.commit();
                    return existing;
                }
                try (PreparedStatement statement = connection.prepareStatement(advanceSql)) {
                    statement.setLong(1, runtimeSequence);
                    statement.setString(2, event.getRunId());
                    statement.setInt(3, leaseAttempt);
                    statement.setLong(4, runtimeSequence - 1);
                    statement.setLong(5, acceptedAt.getTime());
                    if (statement.executeUpdate() != 1) {
                        AgentRuntimeRunLease lease = queryRuntimeRunLease(connection, event.getRunId());
                        if (lease == null || lease.getLeaseAttempt() != leaseAttempt) {
                            throw new SecurityException("stale runtime run lease attempt");
                        }
                        if (acceptedAt.after(lease.getLeaseExpiresAt())) {
                            throw new IllegalStateException("runtime run lease has expired");
                        }
                        throw new IllegalStateException("runtime event sequence must be exactly "
                                + (lease.getLastEventSequence() + 1));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    statement.setString(1, event.getEventId());
                    statement.setString(2, event.getRunId());
                    statement.setString(3, event.getType().name());
                    statement.setString(4, event.getContent());
                    statement.setString(5, JSON.toJSONString(event.getPayload()));
                    statement.setLong(6, event.getOccurredAt().getTime());
                    statement.setLong(7, event.getPersistedAt().getTime());
                    statement.setInt(8, leaseAttempt);
                    statement.setLong(9, runtimeSequence);
                    statement.executeUpdate();
                }
                if (providerSessionId != null) {
                    try (PreparedStatement statement = connection.prepareStatement(sessionSql)) {
                        statement.setString(1, providerSessionId);
                        statement.setLong(2, acceptedAt.getTime());
                        statement.setString(3, event.getRunId());
                        if (statement.executeUpdate() != 1) {
                            throw new ConcurrentModificationException(
                                    "runtime run cannot persist provider session: " + event.getRunId());
                        }
                    }
                }
                connection.commit();
                return getRunEvent(event.getEventId());
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("append runtime run event", exception);
        }
    }

    @Override
    public AgentRuntimeRunLease requestRuntimeRunCancellation(String runId, Date requestedAt) {
        String sql = """
                UPDATE agent_runtime_run_lease
                SET cancel_requested_at = ?, revision = revision + 1
                WHERE run_id = ? AND lease_state = 'ACTIVE' AND cancel_requested_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requestedAt.getTime());
            statement.setString(2, runId);
            statement.executeUpdate();
            return getRuntimeRunLease(runId);
        } catch (SQLException exception) {
            throw storageFailure("request runtime run cancellation", exception);
        }
    }

    @Override
    public AgentRuntimeApproval createOrGetRuntimeApproval(AgentRuntimeApproval approval) {
        AgentRuntimeApproval existing = findRuntimeApproval(approval.getRunId(), approval.getLeaseAttempt(),
                approval.getProviderRequestId());
        if (existing != null) {
            return existing;
        }
        String sql = """
                INSERT INTO agent_runtime_approval (
                    id, run_id, lease_attempt, provider_request_id, tool_call_id, title,
                    request_payload, allow_option_id, reject_option_id, status, requested_at,
                    decided_by, decided_at, decision, reason, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, approval.getId());
            statement.setString(2, approval.getRunId());
            statement.setInt(3, approval.getLeaseAttempt());
            statement.setString(4, approval.getProviderRequestId());
            statement.setString(5, approval.getToolCallId());
            statement.setString(6, approval.getTitle());
            statement.setString(7, JSON.toJSONString(approval.getRequestPayload()));
            statement.setString(8, approval.getAllowOptionId());
            statement.setString(9, approval.getRejectOptionId());
            statement.setString(10, approval.getStatus().name());
            statement.setLong(11, approval.getRequestedAt().getTime());
            setLong(statement, 12, approval.getDecidedBy());
            setDate(statement, 13, approval.getDecidedAt());
            statement.setString(14, approval.getDecision() == null ? null : approval.getDecision().name());
            statement.setString(15, approval.getReason());
            statement.setLong(16, approval.getRevision());
            statement.executeUpdate();
            return getRuntimeApproval(approval.getId());
        } catch (SQLException exception) {
            AgentRuntimeApproval concurrent = findRuntimeApproval(approval.getRunId(),
                    approval.getLeaseAttempt(), approval.getProviderRequestId());
            if (concurrent != null) {
                return concurrent;
            }
            throw storageFailure("create runtime approval", exception);
        }
    }

    @Override
    public AgentRuntimeApproval getRuntimeApproval(String approvalId) {
        return queryRuntimeApproval("SELECT * FROM agent_runtime_approval WHERE id = ?",
                statement -> statement.setString(1, approvalId));
    }

    @Override
    public AgentRuntimeApproval findRuntimeApproval(String runId, int leaseAttempt,
                                                    String providerRequestId) {
        return queryRuntimeApproval("""
                SELECT * FROM agent_runtime_approval
                WHERE run_id = ? AND lease_attempt = ? AND provider_request_id = ?
                """, statement -> {
            statement.setString(1, runId);
            statement.setInt(2, leaseAttempt);
            statement.setString(3, providerRequestId);
        });
    }

    @Override
    public List<AgentRuntimeApproval> listRuntimeApprovals(String runId) {
        String sql = """
                SELECT * FROM agent_runtime_approval
                WHERE run_id = ? ORDER BY requested_at ASC, id ASC
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentRuntimeApproval> approvals = new ArrayList<>();
                while (resultSet.next()) {
                    approvals.add(readRuntimeApproval(resultSet));
                }
                return approvals;
            }
        } catch (SQLException exception) {
            throw storageFailure("list runtime approvals", exception);
        }
    }

    @Override
    public AgentRuntimeApproval updateRuntimeApproval(AgentRuntimeApproval approval, long expectedRevision) {
        String sql = """
                UPDATE agent_runtime_approval
                SET status = ?, decided_by = ?, decided_at = ?, decision = ?, reason = ?, revision = ?
                WHERE id = ? AND revision = ? AND status = 'PENDING'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, approval.getStatus().name());
            setLong(statement, 2, approval.getDecidedBy());
            setDate(statement, 3, approval.getDecidedAt());
            statement.setString(4, approval.getDecision() == null ? null : approval.getDecision().name());
            statement.setString(5, approval.getReason());
            statement.setLong(6, approval.getRevision());
            statement.setString(7, approval.getId());
            statement.setLong(8, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException(
                        "runtime approval revision or status has changed: " + approval.getId());
            }
            return getRuntimeApproval(approval.getId());
        } catch (SQLException exception) {
            throw storageFailure("update runtime approval", exception);
        }
    }

    @Override
    public AgentRuntimeRunLease finishRuntimeRun(AgentRuntimeRunLease lease, AgentRunEvent terminalEvent,
                                                 AgentRunStatusEnum targetStatus, String failureReason,
                                                 String resultSummary, Date completedAt,
                                                 long expectedLeaseRevision, long expectedRunRevision) {
        String leaseSql = """
                UPDATE agent_runtime_run_lease
                SET last_event_sequence = ?, lease_state = ?, released_at = ?, terminal_event_id = ?,
                    revision = revision + 1
                WHERE run_id = ? AND runtime_instance_id = ? AND lease_attempt = ?
                  AND revision = ? AND lease_state = 'ACTIVE' AND last_event_sequence = ?
                  AND lease_expires_at >= ?
                """;
        String runSql = """
                UPDATE agent_run
                SET status = ?, updated_at = ?, completed_at = ?, failure_reason = ?,
                    result_summary = ?, revision = revision + 1
                WHERE id = ? AND revision = ?
                  AND status IN ('DISPATCHED', 'RUNNING', 'WAITING_APPROVAL')
                """;
        String releaseSlotSql = """
                UPDATE agent_runtime_instance
                SET active_runs = CASE WHEN active_runs > 0 THEN active_runs - 1 ELSE 0 END,
                    updated_at = ?, revision = revision + 1
                WHERE id = ?
                """;
        String expireApprovalsSql = """
                UPDATE agent_runtime_approval
                SET status = 'EXPIRED', reason = ?, revision = revision + 1
                WHERE run_id = ? AND status = 'PENDING'
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentRuntimeRunLease current = queryRuntimeRunLease(connection, lease.getRunId());
                if (current == null) {
                    throw new IllegalStateException("runtime run lease not found: " + lease.getRunId());
                }
                if (current.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
                    requireMatchingTerminalLease(current, lease, terminalEvent, targetStatus);
                    connection.commit();
                    return current;
                }
                try (PreparedStatement statement = connection.prepareStatement(leaseSql)) {
                    statement.setLong(1, terminalEvent.getRuntimeSequence());
                    statement.setString(2, leaseState(targetStatus).name());
                    statement.setLong(3, completedAt.getTime());
                    statement.setString(4, terminalEvent.getEventId());
                    statement.setString(5, lease.getRunId());
                    statement.setString(6, lease.getRuntimeInstanceId());
                    statement.setInt(7, lease.getLeaseAttempt());
                    statement.setLong(8, expectedLeaseRevision);
                    statement.setLong(9, terminalEvent.getRuntimeSequence() - 1);
                    statement.setLong(10, completedAt.getTime());
                    if (statement.executeUpdate() != 1) {
                        throw new ConcurrentModificationException(
                                "runtime run lease changed before terminal acknowledgement: " + lease.getRunId());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(runSql)) {
                    statement.setString(1, targetStatus.name());
                    statement.setLong(2, completedAt.getTime());
                    statement.setLong(3, completedAt.getTime());
                    statement.setString(4, failureReason);
                    statement.setString(5, resultSummary);
                    statement.setString(6, lease.getRunId());
                    statement.setLong(7, expectedRunRevision);
                    if (statement.executeUpdate() != 1) {
                        throw new ConcurrentModificationException(
                                "runtime run changed before terminal acknowledgement: " + lease.getRunId());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(releaseSlotSql)) {
                    statement.setLong(1, completedAt.getTime());
                    statement.setString(2, lease.getRuntimeInstanceId());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "runtime instance slot was not reserved: " + lease.getRuntimeInstanceId());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(expireApprovalsSql)) {
                    statement.setString(1, "Run entered terminal state: " + targetStatus.name());
                    statement.setString(2, lease.getRunId());
                    statement.executeUpdate();
                }
                insertRuntimeRunEvent(connection, terminalEvent);
                connection.commit();
                return getRuntimeRunLease(lease.getRunId());
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("finish runtime run", exception);
        }
    }

    @Override
    public AgentRuntimeRunLease suspendRuntimeRun(AgentRuntimeRunLease lease, AgentRunEvent suspendEvent,
                                                  AgentRunStatusEnum targetRunStatus, Date suspendedAt,
                                                  long expectedLeaseRevision, long expectedRunRevision) {
        if (targetRunStatus != AgentRunStatusEnum.WAITING_APPROVAL
                && targetRunStatus != AgentRunStatusEnum.QUEUED) {
            throw new IllegalArgumentException("approval suspension target must remain waiting or become queued");
        }
        String leaseSql = """
                UPDATE agent_runtime_run_lease
                SET last_event_sequence = ?, lease_state = 'SUSPENDED', released_at = ?, terminal_event_id = ?,
                    revision = revision + 1
                WHERE run_id = ? AND runtime_instance_id = ? AND lease_attempt = ?
                  AND revision = ? AND lease_state = 'ACTIVE' AND last_event_sequence = ?
                  AND lease_expires_at >= ?
                """;
        String runSql = """
                UPDATE agent_run
                SET status = ?, updated_at = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND status = 'WAITING_APPROVAL'
                """;
        String releaseSlotSql = """
                UPDATE agent_runtime_instance
                SET active_runs = CASE WHEN active_runs > 0 THEN active_runs - 1 ELSE 0 END,
                    updated_at = ?, revision = revision + 1
                WHERE id = ?
                """;
        String expireRuntimeApprovalsSql = """
                UPDATE agent_runtime_approval
                SET status = 'EXPIRED', reason = 'Provider suspended for SQL approval continuation',
                    revision = revision + 1
                WHERE run_id = ? AND status = 'PENDING'
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentRuntimeRunLease current = queryRuntimeRunLease(connection, lease.getRunId());
                if (current == null) {
                    throw new IllegalStateException("runtime run lease not found: " + lease.getRunId());
                }
                if (current.getState() != AgentRuntimeLeaseStateEnum.ACTIVE) {
                    if (current.getState() != AgentRuntimeLeaseStateEnum.SUSPENDED
                            || !Objects.equals(current.getTerminalEventId(), suspendEvent.getEventId())) {
                        throw new IllegalStateException(
                                "runtime run already has a different lease outcome: " + lease.getRunId());
                    }
                    connection.commit();
                    return current;
                }
                try (PreparedStatement statement = connection.prepareStatement(leaseSql)) {
                    statement.setLong(1, suspendEvent.getRuntimeSequence());
                    statement.setLong(2, suspendedAt.getTime());
                    statement.setString(3, suspendEvent.getEventId());
                    statement.setString(4, lease.getRunId());
                    statement.setString(5, lease.getRuntimeInstanceId());
                    statement.setInt(6, lease.getLeaseAttempt());
                    statement.setLong(7, expectedLeaseRevision);
                    statement.setLong(8, suspendEvent.getRuntimeSequence() - 1);
                    statement.setLong(9, suspendedAt.getTime());
                    if (statement.executeUpdate() != 1) {
                        throw new ConcurrentModificationException(
                                "runtime run lease changed before approval suspension: " + lease.getRunId());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(runSql)) {
                    statement.setString(1, targetRunStatus.name());
                    statement.setLong(2, suspendedAt.getTime());
                    statement.setString(3, lease.getRunId());
                    statement.setLong(4, expectedRunRevision);
                    if (statement.executeUpdate() != 1) {
                        throw new ConcurrentModificationException(
                                "runtime run changed before approval suspension: " + lease.getRunId());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(releaseSlotSql)) {
                    statement.setLong(1, suspendedAt.getTime());
                    statement.setString(2, lease.getRuntimeInstanceId());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "runtime instance slot was not reserved: " + lease.getRuntimeInstanceId());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(expireRuntimeApprovalsSql)) {
                    statement.setString(1, lease.getRunId());
                    statement.executeUpdate();
                }
                insertRuntimeRunEvent(connection, suspendEvent);
                connection.commit();
                return getRuntimeRunLease(lease.getRunId());
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("suspend runtime run for SQL approval", exception);
        }
    }

    @Override
    public List<String> reconcileExpiredRuntimeRuns(Date expiredAt, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("positive runtime reconciliation limit is required");
        }
        String candidatesSql = """
                SELECT run_id FROM agent_runtime_run_lease
                WHERE lease_state = 'ACTIVE' AND lease_expires_at < ?
                ORDER BY lease_expires_at ASC, run_id ASC
                LIMIT ?
                """;
        List<String> candidates = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(candidatesSql)) {
            statement.setLong(1, expiredAt.getTime());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(resultSet.getString("run_id"));
                }
            }
        } catch (SQLException exception) {
            throw storageFailure("list expired runtime run leases", exception);
        }
        List<String> reconciled = new ArrayList<>();
        for (String runId : candidates) {
            if (reconcileExpiredRuntimeRun(runId, expiredAt)) {
                reconciled.add(runId);
            }
        }
        return reconciled;
    }

    @Override
    public AgentTaskSchedule createSchedule(AgentTaskSchedule schedule) {
        String sql = """
                INSERT INTO agent_task_schedule (
                    id, name, task_title, task_description, acceptance_criteria,
                    assignee_agent_id, priority, data_scope_snapshot_json,
                    schedule_type, scheduled_at, cron_expression, timezone, status,
                    concurrency_policy, catch_up_policy, next_run_at, last_run_at,
                    created_by, created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindSchedule(statement, schedule);
            statement.executeUpdate();
            return getSchedule(schedule.getId());
        } catch (SQLException exception) {
            throw storageFailure("create agent task schedule", exception);
        }
    }

    @Override
    public AgentTaskSchedule updateSchedule(AgentTaskSchedule schedule, long expectedRevision) {
        String sql = """
                UPDATE agent_task_schedule SET
                    name = ?, task_title = ?, task_description = ?, acceptance_criteria = ?,
                    assignee_agent_id = ?, priority = ?, data_scope_snapshot_json = ?,
                    schedule_type = ?, scheduled_at = ?, cron_expression = ?, timezone = ?,
                    status = ?, concurrency_policy = ?, catch_up_policy = ?,
                    next_run_at = ?, last_run_at = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, schedule.getName());
            statement.setString(index++, schedule.getTaskTitle());
            statement.setString(index++, schedule.getTaskDescription());
            statement.setString(index++, schedule.getAcceptanceCriteria());
            statement.setString(index++, schedule.getAssigneeAgentId());
            statement.setInt(index++, schedule.getPriority());
            statement.setString(index++, JSON.toJSONString(schedule.getDataScopeSnapshot()));
            statement.setString(index++, schedule.getScheduleType().name());
            setDate(statement, index++, schedule.getScheduledAt());
            statement.setString(index++, schedule.getCronExpression());
            statement.setString(index++, schedule.getTimezone());
            statement.setString(index++, schedule.getStatus().name());
            statement.setString(index++, schedule.getConcurrencyPolicy().name());
            statement.setString(index++, schedule.getCatchUpPolicy().name());
            setDate(statement, index++, schedule.getNextRunAt());
            setDate(statement, index++, schedule.getLastRunAt());
            statement.setLong(index++, schedule.getGmtModified().getTime());
            statement.setLong(index++, schedule.getRevision());
            statement.setString(index++, schedule.getId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("schedule revision has changed: " + schedule.getId());
            }
            return getSchedule(schedule.getId());
        } catch (SQLException exception) {
            throw storageFailure("update agent task schedule", exception);
        }
    }

    @Override
    public AgentTaskSchedule getSchedule(String id) {
        return querySchedule("SELECT * FROM agent_task_schedule WHERE id = ?", statement ->
                statement.setString(1, id));
    }

    @Override
    public List<AgentTaskSchedule> listSchedules(Long createdBy) {
        String sql = """
                SELECT * FROM agent_task_schedule
                WHERE created_by = ? ORDER BY updated_at DESC, id ASC
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setLong(statement, 1, createdBy);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentTaskSchedule> result = new ArrayList<>();
                while (resultSet.next()) result.add(readSchedule(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list agent task schedules", exception);
        }
    }

    @Override
    public List<AgentTaskSchedule> listDueSchedules(Date dueAt, int limit) {
        String sql = """
                SELECT * FROM agent_task_schedule
                WHERE status = 'ACTIVE' AND next_run_at IS NOT NULL AND next_run_at <= ?
                ORDER BY next_run_at ASC, id ASC LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, dueAt.getTime());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentTaskSchedule> result = new ArrayList<>();
                while (resultSet.next()) result.add(readSchedule(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list due agent task schedules", exception);
        }
    }

    @Override
    public AgentTaskScheduleClaim claimExecution(AgentTaskScheduleExecution execution, Date now) {
        String insert = """
                INSERT INTO agent_task_schedule_execution (
                    id, schedule_id, source, planned_at, status, task_id, run_id,
                    attempt, lease_token, lease_expires_at, reason_code, failure_reason,
                    created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(insert)) {
            bindScheduleExecution(statement, execution);
            statement.executeUpdate();
            return new AgentTaskScheduleClaim(getExecution(execution.getId()), true);
        } catch (SQLException exception) {
            if (!"23505".equals(exception.getSQLState())) {
                throw storageFailure("claim agent task schedule execution", exception);
            }
        }

        AgentTaskScheduleExecution existing = findExecution(
                execution.getScheduleId(), execution.getPlannedAt(), execution.getSource());
        if (existing == null || existing.getStatus() != AgentTaskScheduleExecutionStatusEnum.CLAIMED
                || existing.getLeaseExpiresAt() == null || !existing.getLeaseExpiresAt().before(now)) {
            return new AgentTaskScheduleClaim(existing, false);
        }
        String reclaim = """
                UPDATE agent_task_schedule_execution SET attempt = attempt + 1,
                    lease_token = ?, lease_expires_at = ?, updated_at = ?, revision = revision + 1,
                    reason_code = NULL, failure_reason = NULL
                WHERE id = ? AND revision = ? AND status = 'CLAIMED' AND lease_expires_at < ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(reclaim)) {
            statement.setString(1, execution.getLeaseToken());
            statement.setLong(2, execution.getLeaseExpiresAt().getTime());
            statement.setLong(3, now.getTime());
            statement.setString(4, existing.getId());
            statement.setLong(5, existing.getRevision());
            statement.setLong(6, now.getTime());
            boolean claimed = statement.executeUpdate() == 1;
            return new AgentTaskScheduleClaim(getExecution(existing.getId()), claimed);
        } catch (SQLException exception) {
            throw storageFailure("reclaim agent task schedule execution", exception);
        }
    }

    @Override
    public AgentTaskScheduleExecution getExecution(String id) {
        AgentTaskScheduleExecution execution = queryExecution(
                "SELECT * FROM agent_task_schedule_execution WHERE id = ?", statement ->
                        statement.setString(1, id));
        resolveTaskLinkState(execution);
        return execution;
    }

    @Override
    public List<AgentTaskScheduleExecution> listExecutions(String scheduleId) {
        String sql = """
                SELECT * FROM agent_task_schedule_execution
                WHERE schedule_id = ? ORDER BY planned_at DESC, id ASC
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scheduleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentTaskScheduleExecution> result = new ArrayList<>();
                while (resultSet.next()) {
                    AgentTaskScheduleExecution execution = readScheduleExecution(resultSet);
                    resolveTaskLinkState(execution);
                    result.add(execution);
                }
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list agent task schedule executions", exception);
        }
    }

    @Override
    public List<AgentTaskScheduleExecution> listRecoverableExecutions(Date now, int limit) {
        String sql = """
                SELECT * FROM agent_task_schedule_execution
                WHERE status = 'TASK_CREATED'
                   OR (status = 'CLAIMED' AND lease_expires_at < ?)
                ORDER BY updated_at ASC, id ASC LIMIT ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now.getTime());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentTaskScheduleExecution> result = new ArrayList<>();
                while (resultSet.next()) result.add(readScheduleExecution(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list recoverable schedule executions", exception);
        }
    }

    @Override
    public AgentTaskCreation createScheduledTask(AgentTaskSchedule schedule,
                                                 AgentTaskScheduleExecution execution,
                                                 AgentTask task, AgentRun run,
                                                 Date nextRunAt, long expectedScheduleRevision,
                                                 long expectedExecutionRevision, String leaseToken) {
        String taskSql = """
                INSERT INTO agent_task (
                    id, title, description, acceptance_criteria, status, priority,
                    assignee_agent_id, created_by, origin_type, origin_session_id,
                    origin_message_id, origin_schedule_id, origin_schedule_execution_id, planned_at,
                    data_scope_snapshot_json, data_scope_synced_at,
                    data_scope_synced_from_agent_revision, current_run_id,
                    created_at, updated_at, completed_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String runSql = """
                INSERT INTO agent_run (
                    id, task_id, agent_id, runtime_type, runtime_profile_id, runtime_provider,
                    runtime_profile_snapshot, provider_session_id, trigger_type, status,
                    attempt, parent_run_id, created_at, updated_at, started_at,
                    completed_at, failure_reason, result_summary, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String executionSql = """
                UPDATE agent_task_schedule_execution SET status = 'TASK_CREATED', task_id = ?, run_id = ?,
                    updated_at = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND lease_token = ? AND status = 'CLAIMED'
                """;
        String scheduleSql = """
                UPDATE agent_task_schedule SET status = ?, next_run_at = ?, last_run_at = ?,
                    updated_at = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND status <> 'ARCHIVED'
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement taskStatement = connection.prepareStatement(taskSql);
                 PreparedStatement runStatement = connection.prepareStatement(runSql);
                 PreparedStatement executionStatement = connection.prepareStatement(executionSql);
                 PreparedStatement scheduleStatement = connection.prepareStatement(scheduleSql)) {
                bindTask(taskStatement, task);
                taskStatement.executeUpdate();
                bindRun(runStatement, run);
                runStatement.executeUpdate();
                executionStatement.setString(1, task.getId());
                executionStatement.setString(2, run.getId());
                executionStatement.setLong(3, task.getGmtCreate().getTime());
                executionStatement.setString(4, execution.getId());
                executionStatement.setLong(5, expectedExecutionRevision);
                executionStatement.setString(6, leaseToken);
                if (executionStatement.executeUpdate() != 1) {
                    throw new ConcurrentModificationException("schedule execution lease changed: " + execution.getId());
                }
                scheduleStatement.setString(1, schedule.getStatus().name());
                setDate(scheduleStatement, 2, nextRunAt);
                scheduleStatement.setLong(3, execution.getPlannedAt().getTime());
                scheduleStatement.setLong(4, task.getGmtCreate().getTime());
                scheduleStatement.setString(5, schedule.getId());
                scheduleStatement.setLong(6, expectedScheduleRevision);
                if (scheduleStatement.executeUpdate() != 1) {
                    throw new ConcurrentModificationException("schedule revision changed: " + schedule.getId());
                }
                connection.commit();
                return new AgentTaskCreation(getTask(task.getId()), getRun(run.getId()));
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("create scheduled task", exception);
        }
    }

    @Override
    public AgentTaskScheduleExecution finishExecutionWithoutTask(AgentTaskSchedule schedule,
                                                                 AgentTaskScheduleExecution execution,
                                                                 Date nextRunAt,
                                                                 long expectedScheduleRevision,
                                                                 long expectedExecutionRevision,
                                                                 String leaseToken) {
        String executionSql = """
                UPDATE agent_task_schedule_execution SET status = ?, reason_code = ?, failure_reason = ?,
                    updated_at = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND lease_token = ? AND status = 'CLAIMED'
                """;
        String scheduleSql = """
                UPDATE agent_task_schedule SET status = ?, next_run_at = ?, last_run_at = ?,
                    updated_at = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND status <> 'ARCHIVED'
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement executionStatement = connection.prepareStatement(executionSql);
                 PreparedStatement scheduleStatement = connection.prepareStatement(scheduleSql)) {
                executionStatement.setString(1, execution.getStatus().name());
                executionStatement.setString(2, execution.getReasonCode() == null
                        ? null : execution.getReasonCode().name());
                executionStatement.setString(3, execution.getFailureReason());
                executionStatement.setLong(4, execution.getGmtModified().getTime());
                executionStatement.setString(5, execution.getId());
                executionStatement.setLong(6, expectedExecutionRevision);
                executionStatement.setString(7, leaseToken);
                if (executionStatement.executeUpdate() != 1) {
                    throw new ConcurrentModificationException("schedule execution lease changed: " + execution.getId());
                }
                scheduleStatement.setString(1, schedule.getStatus().name());
                setDate(scheduleStatement, 2, nextRunAt);
                scheduleStatement.setLong(3, execution.getPlannedAt().getTime());
                scheduleStatement.setLong(4, execution.getGmtModified().getTime());
                scheduleStatement.setString(5, schedule.getId());
                scheduleStatement.setLong(6, expectedScheduleRevision);
                if (scheduleStatement.executeUpdate() != 1) {
                    throw new ConcurrentModificationException("schedule revision changed: " + schedule.getId());
                }
                connection.commit();
                return getExecution(execution.getId());
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("finish schedule execution without task", exception);
        }
    }

    @Override
    public AgentTaskScheduleExecution updateExecution(AgentTaskScheduleExecution execution,
                                                      long expectedRevision, String leaseToken) {
        String sql = """
                UPDATE agent_task_schedule_execution SET status = ?, task_id = ?, run_id = ?,
                    lease_expires_at = ?, reason_code = ?, failure_reason = ?,
                    updated_at = ?, revision = ?
                WHERE id = ? AND revision = ? AND (? IS NULL OR lease_token = ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, execution.getStatus().name());
            statement.setString(2, execution.getTaskId());
            statement.setString(3, execution.getRunId());
            setDate(statement, 4, execution.getLeaseExpiresAt());
            statement.setString(5, execution.getReasonCode() == null ? null : execution.getReasonCode().name());
            statement.setString(6, execution.getFailureReason());
            statement.setLong(7, execution.getGmtModified().getTime());
            statement.setLong(8, execution.getRevision());
            statement.setString(9, execution.getId());
            statement.setLong(10, expectedRevision);
            statement.setString(11, leaseToken);
            statement.setString(12, leaseToken);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("schedule execution revision changed: " + execution.getId());
            }
            return getExecution(execution.getId());
        } catch (SQLException exception) {
            throw storageFailure("update schedule execution", exception);
        }
    }

    @Override
    public boolean hasActiveExecutionTask(String scheduleId) {
        String sql = """
                SELECT 1 FROM agent_task t JOIN agent_run r ON r.id = t.current_run_id
                WHERE t.origin_schedule_id = ? AND t.archived_at IS NULL
                  AND r.status IN ('QUEUED', 'DISPATCHED', 'RUNNING', 'WAITING_APPROVAL')
                LIMIT 1
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scheduleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw storageFailure("check active schedule task", exception);
        }
    }

    @Override
    public AgentTaskCreation createTaskWithInitialRun(AgentTask task, AgentRun run) {
        String taskSql = """
                INSERT INTO agent_task (
                    id, title, description, acceptance_criteria, status, priority,
                    assignee_agent_id, created_by, origin_type, origin_session_id,
                    origin_message_id, origin_schedule_id, origin_schedule_execution_id, planned_at,
                    data_scope_snapshot_json, data_scope_synced_at,
                    data_scope_synced_from_agent_revision, current_run_id,
                    created_at, updated_at, completed_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String runSql = """
                INSERT INTO agent_run (
                    id, task_id, agent_id, runtime_type, runtime_profile_id, runtime_provider,
                    runtime_profile_snapshot, provider_session_id,
                    trigger_type, status, attempt, parent_run_id, created_at,
                    updated_at, started_at, completed_at, failure_reason,
                    result_summary, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement taskStatement = connection.prepareStatement(taskSql);
                 PreparedStatement runStatement = connection.prepareStatement(runSql)) {
                bindTask(taskStatement, task);
                taskStatement.executeUpdate();
                bindRun(runStatement, run);
                runStatement.executeUpdate();
                connection.commit();
                return new AgentTaskCreation(getTask(task.getId()), getRun(run.getId()));
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("create task and initial run", exception);
        }
    }

    @Override
    public AgentTaskCreation appendTaskRun(AgentTask task, AgentRun run, long expectedTaskRevision) {
        String taskSql = """
                UPDATE agent_task SET current_run_id = ?, status = ?, completed_at = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        String runSql = """
                INSERT INTO agent_run (
                    id, task_id, agent_id, runtime_type, runtime_profile_id, runtime_provider,
                    runtime_profile_snapshot, provider_session_id,
                    trigger_type, status, attempt, parent_run_id, created_at,
                    updated_at, started_at, completed_at, failure_reason,
                    result_summary, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement taskStatement = connection.prepareStatement(taskSql);
                 PreparedStatement runStatement = connection.prepareStatement(runSql)) {
                bindRun(runStatement, run);
                runStatement.executeUpdate();
                taskStatement.setString(1, run.getId());
                taskStatement.setString(2, task.getStatus().name());
                setDate(taskStatement, 3, task.getCompletedAt());
                taskStatement.setLong(4, task.getGmtModified().getTime());
                taskStatement.setLong(5, task.getRevision());
                taskStatement.setString(6, task.getId());
                taskStatement.setLong(7, expectedTaskRevision);
                if (taskStatement.executeUpdate() != 1) {
                    throw new ConcurrentModificationException("task revision has changed: " + task.getId());
                }
                connection.commit();
                return new AgentTaskCreation(getTask(task.getId()), getRun(run.getId()));
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("append task run", exception);
        }
    }

    @Override
    public AgentTask getTask(String id) {
        String sql = "SELECT * FROM agent_task WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readTask(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get task", exception);
        }
    }

    @Override
    public List<AgentTask> listTasks() {
        String sql = "SELECT * FROM agent_task WHERE archived_at IS NULL "
                + "ORDER BY priority DESC, created_at DESC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<AgentTask> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(readTask(resultSet));
            }
            return result;
        } catch (SQLException exception) {
            throw storageFailure("list tasks", exception);
        }
    }

    @Override
    public List<AgentTask> listArchivedTasks() {
        String sql = "SELECT * FROM agent_task WHERE archived_at IS NOT NULL "
                + "ORDER BY archived_at DESC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<AgentTask> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(readTask(resultSet));
            }
            return result;
        } catch (SQLException exception) {
            throw storageFailure("list archived tasks", exception);
        }
    }

    @Override
    public AgentTask updateTask(AgentTask task, long expectedRevision) {
        String sql = """
                UPDATE agent_task SET
                    status = ?, data_scope_snapshot_json = ?, data_scope_synced_at = ?,
                    data_scope_synced_from_agent_revision = ?, updated_at = ?, completed_at = ?, archived_at = ?,
                    revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, task.getStatus().name());
            statement.setString(index++, JSON.toJSONString(task.getDataScopeSnapshot()));
            setDate(statement, index++, task.getDataScopeSyncedAt());
            setLong(statement, index++, task.getDataScopeSyncedFromAgentRevision());
            statement.setLong(index++, task.getGmtModified().getTime());
            setDate(statement, index++, task.getCompletedAt());
            setDate(statement, index++, task.getArchivedAt());
            statement.setLong(index++, task.getRevision());
            statement.setString(index++, task.getId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("task revision has changed: " + task.getId());
            }
            return getTask(task.getId());
        } catch (SQLException exception) {
            throw storageFailure("update task", exception);
        }
    }

    @Override
    public void deleteTask(String taskId, long expectedRevision) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                deleteByTask(connection, "DELETE FROM agent_artifact_dashboard_ref WHERE task_id = ?", taskId);
                deleteByTask(connection, "DELETE FROM agent_task_context WHERE task_id = ?", taskId);
                deleteByTask(connection, "DELETE FROM agent_artifact_evidence WHERE artifact_id IN "
                        + "(SELECT id FROM agent_artifact WHERE task_id = ?)", taskId);
                deleteByTask(connection, "DELETE FROM agent_artifact_version WHERE artifact_id IN "
                        + "(SELECT id FROM agent_artifact WHERE task_id = ?)", taskId);
                deleteByTask(connection, "DELETE FROM agent_artifact WHERE task_id = ?", taskId);
                deleteByTask(connection, "DELETE FROM agent_tool_attempt WHERE run_id IN "
                        + "(SELECT id FROM agent_run WHERE task_id = ?)", taskId);
                deleteByTask(connection, "DELETE FROM agent_approval WHERE run_id IN "
                        + "(SELECT id FROM agent_run WHERE task_id = ?)", taskId);
                deleteByTask(connection, "DELETE FROM agent_sql_proposal WHERE run_id IN "
                        + "(SELECT id FROM agent_run WHERE task_id = ?)", taskId);
                deleteByTask(connection, "DELETE FROM agent_run_event WHERE run_id IN "
                        + "(SELECT id FROM agent_run WHERE task_id = ?)", taskId);
                deleteByTask(connection, "DELETE FROM agent_runtime_approval WHERE run_id IN "
                        + "(SELECT id FROM agent_run WHERE task_id = ?)", taskId);
                deleteByTask(connection, "DELETE FROM agent_runtime_run_lease WHERE run_id IN "
                        + "(SELECT id FROM agent_run WHERE task_id = ?)", taskId);
                deleteByTask(connection, "UPDATE agent_run SET parent_run_id = NULL WHERE task_id = ?", taskId);
                deleteByTask(connection, "DELETE FROM agent_run WHERE task_id = ?", taskId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM agent_task WHERE id = ? AND revision = ? AND archived_at IS NOT NULL")) {
                    statement.setString(1, taskId);
                    statement.setLong(2, expectedRevision);
                    if (statement.executeUpdate() != 1) {
                        throw new ConcurrentModificationException("task revision has changed: " + taskId);
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("delete archived task", exception);
        }
    }

    private void deleteByTask(Connection connection, String sql, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            statement.executeUpdate();
        }
    }

    @Override
    public AgentRun getRun(String id) {
        String sql = "SELECT * FROM agent_run WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRun(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get run", exception);
        }
    }

    @Override
    public List<AgentRun> listRunsByTask(String taskId) {
        String sql = "SELECT * FROM agent_run WHERE task_id = ? ORDER BY created_at ASC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentRun> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(readRun(resultSet));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list task runs", exception);
        }
    }

    @Override
    public AgentRun updateRun(AgentRun run, long expectedRevision) {
        String sql = """
                UPDATE agent_run SET
                    status = ?, updated_at = ?, started_at = ?, completed_at = ?,
                    failure_reason = ?, result_summary = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, run.getStatus().name());
            statement.setLong(index++, run.getGmtModified().getTime());
            setDate(statement, index++, run.getStartedAt());
            setDate(statement, index++, run.getCompletedAt());
            statement.setString(index++, run.getFailureReason());
            statement.setString(index++, run.getResultSummary());
            statement.setLong(index++, run.getRevision());
            statement.setString(index++, run.getId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("run revision has changed: " + run.getId());
            }
            return getRun(run.getId());
        } catch (SQLException exception) {
            throw storageFailure("update run", exception);
        }
    }

    @Override
    public AgentRunEvent appendRunEvent(AgentRunEvent event) {
        String sql = """
                INSERT INTO agent_run_event (
                    event_id, run_id, event_type, content, payload_json,
                    occurred_at, persisted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            Date persistedAt = event.getPersistedAt() == null ? new Date() : event.getPersistedAt();
            statement.setString(1, event.getEventId());
            statement.setString(2, event.getRunId());
            statement.setString(3, event.getType().name());
            statement.setString(4, event.getContent());
            statement.setString(5, JSON.toJSONString(event.getPayload()));
            statement.setLong(6, event.getOccurredAt().getTime());
            statement.setLong(7, persistedAt.getTime());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("agent run event sequence was not generated");
                }
                return getRunEvent(keys.getLong(1));
            }
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                AgentRunEvent existing = getRunEvent(event.getEventId());
                if (existing != null && existing.getRunId().equals(event.getRunId())) {
                    return existing;
                }
            }
            throw storageFailure("append run event", exception);
        }
    }

    @Override
    public List<AgentRunEvent> listRunEvents(String runId) {
        String sql = "SELECT * FROM agent_run_event WHERE run_id = ? ORDER BY event_order ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentRunEvent> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(readRunEvent(resultSet));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list run events", exception);
        }
    }

    @Override
    public AgentTaskContext appendTaskContext(AgentTaskContext context) {
        String sql = """
                INSERT INTO agent_task_context (
                    id, task_id, context_type, title, content, attachment_name,
                    attachment_mime_type, attachment_size, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.getId());
            statement.setString(2, context.getTaskId());
            statement.setString(3, context.getType().name());
            statement.setString(4, context.getTitle());
            statement.setString(5, context.getContent());
            statement.setString(6, context.getAttachmentName());
            statement.setString(7, context.getAttachmentMimeType());
            setLong(statement, 8, context.getAttachmentSize());
            setLong(statement, 9, context.getCreatedBy());
            statement.setLong(10, context.getCreatedAt().getTime());
            statement.executeUpdate();
            return getTaskContext(context.getId());
        } catch (SQLException exception) {
            throw storageFailure("append task context", exception);
        }
    }

    @Override
    public List<AgentTaskContext> listTaskContexts(String taskId) {
        String sql = """
                SELECT * FROM agent_task_context
                WHERE task_id = ? ORDER BY created_at ASC, id ASC
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentTaskContext> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(readTaskContext(resultSet));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list task contexts", exception);
        }
    }

    @Override
    public AgentArtifactDetail createArtifact(AgentArtifact artifact, AgentArtifactVersion version,
                                              List<AgentArtifactEvidence> evidence) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertArtifact(connection, artifact);
                insertArtifactVersion(connection, version);
                insertArtifactEvidence(connection, evidence);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
            return artifactDetail(artifact.getId());
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState()) && artifact.getCreatedByRunId() != null) {
                AgentArtifact existing = getArtifactByRunAndType(
                        artifact.getTaskId(), artifact.getCreatedByRunId(), artifact.getType());
                if (existing != null) {
                    return artifactDetail(existing.getId());
                }
            }
            throw storageFailure("create artifact", exception);
        }
    }

    @Override
    public AgentArtifactDetail appendArtifactVersion(AgentArtifact artifact, AgentArtifactVersion version,
                                                     List<AgentArtifactEvidence> evidence, long expectedRevision) {
        String updateSql = """
                UPDATE agent_artifact SET current_version = ?, status = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                update.setInt(1, artifact.getCurrentVersion());
                update.setString(2, artifact.getStatus().name());
                update.setLong(3, artifact.getGmtModified().getTime());
                update.setLong(4, artifact.getRevision());
                update.setString(5, artifact.getId());
                update.setLong(6, expectedRevision);
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    throw new ConcurrentModificationException(
                            "artifact revision has changed: " + artifact.getId());
                }
                insertArtifactVersion(connection, version);
                insertArtifactEvidence(connection, evidence);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
            return artifactDetail(artifact.getId());
        } catch (SQLException exception) {
            throw storageFailure("append artifact version", exception);
        }
    }

    @Override
    public AgentArtifact getArtifact(String id) {
        return queryArtifact("SELECT * FROM agent_artifact WHERE id = ?", statement -> statement.setString(1, id));
    }

    @Override
    public AgentArtifact getArtifactByRunAndType(String taskId, String runId, AgentArtifactTypeEnum type) {
        return queryArtifact("""
                SELECT * FROM agent_artifact
                WHERE task_id = ? AND created_by_run_id = ? AND artifact_type = ?
                """, statement -> {
            statement.setString(1, taskId);
            statement.setString(2, runId);
            statement.setString(3, type.name());
        });
    }

    @Override
    public List<AgentArtifact> listArtifactsByTask(String taskId) {
        String sql = "SELECT * FROM agent_artifact WHERE task_id = ? ORDER BY updated_at DESC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentArtifact> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(readArtifact(resultSet));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list task artifacts", exception);
        }
    }

    @Override
    public List<AgentArtifactVersion> listArtifactVersions(String artifactId) {
        String sql = "SELECT * FROM agent_artifact_version WHERE artifact_id = ? ORDER BY version ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentArtifactVersion> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(readArtifactVersion(resultSet));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list artifact versions", exception);
        }
    }

    @Override
    public List<AgentArtifactEvidence> listArtifactEvidence(String artifactId) {
        String sql = """
                SELECT * FROM agent_artifact_evidence
                WHERE artifact_id = ? ORDER BY artifact_version ASC, created_at ASC, id ASC
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentArtifactEvidence> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(readArtifactEvidence(resultSet));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list artifact evidence", exception);
        }
    }

    @Override
    public AgentSqlProposal createSqlProposal(AgentSqlProposal proposal, AgentApproval approval) {
        String supersede = """
                UPDATE agent_sql_proposal SET status = 'SUPERSEDED', updated_at = ?, revision = revision + 1
                WHERE run_id = ? AND status = 'ACTIVE'
                """;
        String expireApprovals = """
                UPDATE agent_approval SET status = 'EXPIRED', revision = revision + 1
                WHERE run_id = ? AND status = 'PENDING'
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement supersedeStatement = connection.prepareStatement(supersede);
                 PreparedStatement expireStatement = connection.prepareStatement(expireApprovals)) {
                supersedeStatement.setLong(1, proposal.getCreatedAt().getTime());
                supersedeStatement.setString(2, proposal.getRunId());
                supersedeStatement.executeUpdate();
                expireStatement.setString(1, proposal.getRunId());
                expireStatement.executeUpdate();
                insertSqlProposal(connection, proposal);
                if (approval != null) {
                    insertApproval(connection, approval);
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
            return getSqlProposal(proposal.getId());
        } catch (SQLException exception) {
            throw storageFailure("create SQL proposal", exception);
        }
    }

    @Override
    public AgentSqlProposal getSqlProposal(String id) {
        return querySqlProposal("SELECT * FROM agent_sql_proposal WHERE id = ?",
                statement -> statement.setString(1, id));
    }

    @Override
    public AgentSqlProposal findSqlProposal(String runId, String sqlHash, Long dataSourceId,
                                            String databaseName, String schemaName) {
        String sql = """
                SELECT * FROM agent_sql_proposal
                WHERE run_id = ? AND sql_hash = ? AND data_source_id = ?
                  AND ((database_name IS NULL AND ? IS NULL) OR LOWER(database_name) = LOWER(?))
                  AND ((schema_name IS NULL AND ? IS NULL) OR LOWER(schema_name) = LOWER(?))
                ORDER BY proposal_version DESC LIMIT 1
                """;
        return querySqlProposal(sql, statement -> {
            statement.setString(1, runId);
            statement.setString(2, sqlHash);
            setLong(statement, 3, dataSourceId);
            statement.setString(4, databaseName);
            statement.setString(5, databaseName);
            statement.setString(6, schemaName);
            statement.setString(7, schemaName);
        });
    }

    @Override
    public List<AgentSqlProposal> listSqlProposals(String runId) {
        String sql = "SELECT * FROM agent_sql_proposal WHERE run_id = ? ORDER BY proposal_version ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentSqlProposal> result = new ArrayList<>();
                while (resultSet.next()) result.add(readSqlProposal(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list SQL proposals", exception);
        }
    }

    @Override
    public AgentSqlProposal updateSqlProposal(AgentSqlProposal proposal, long expectedRevision) {
        String sql = """
                UPDATE agent_sql_proposal SET status = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, proposal.getStatus().name());
            statement.setLong(2, proposal.getUpdatedAt().getTime());
            statement.setLong(3, proposal.getRevision());
            statement.setString(4, proposal.getId());
            statement.setLong(5, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("SQL proposal revision has changed: " + proposal.getId());
            }
            return getSqlProposal(proposal.getId());
        } catch (SQLException exception) {
            throw storageFailure("update SQL proposal", exception);
        }
    }

    @Override
    public AgentApproval getApproval(String id) {
        return queryApproval("SELECT * FROM agent_approval WHERE id = ?", statement -> statement.setString(1, id));
    }

    @Override
    public AgentApproval findApprovalByProposal(String proposalId) {
        return queryApproval("SELECT * FROM agent_approval WHERE proposal_id = ?",
                statement -> statement.setString(1, proposalId));
    }

    @Override
    public List<AgentApproval> listApprovals(String runId) {
        String sql = "SELECT * FROM agent_approval WHERE run_id = ? ORDER BY requested_at ASC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentApproval> result = new ArrayList<>();
                while (resultSet.next()) result.add(readApproval(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list approvals", exception);
        }
    }

    @Override
    public AgentApproval updateApproval(AgentApproval approval, long expectedRevision) {
        String sql = """
                UPDATE agent_approval SET status = ?, decided_by = ?, decided_at = ?, decision = ?,
                    reason = ?, revision = ? WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, approval.getStatus().name());
            setLong(statement, 2, approval.getDecidedBy());
            setDate(statement, 3, approval.getDecidedAt());
            statement.setString(4, approval.getDecision() == null ? null : approval.getDecision().name());
            statement.setString(5, approval.getReason());
            statement.setLong(6, approval.getRevision());
            statement.setString(7, approval.getId());
            statement.setLong(8, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("approval revision has changed: " + approval.getId());
            }
            return getApproval(approval.getId());
        } catch (SQLException exception) {
            throw storageFailure("update approval", exception);
        }
    }

    @Override
    public AgentToolAttempt createOrGetToolAttempt(AgentToolAttempt attempt) {
        try (Connection connection = dataSource.getConnection()) {
            insertToolAttempt(connection, attempt);
            return getToolAttempt(attempt.getId());
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                AgentToolAttempt existing = findToolAttempt(
                        attempt.getRunId(), attempt.getProposalVersion(), attempt.getToolCallId());
                if (existing != null) return existing;
            }
            throw storageFailure("create tool attempt", exception);
        }
    }

    @Override
    public AgentToolAttempt getToolAttempt(String id) {
        return queryToolAttempt("SELECT * FROM agent_tool_attempt WHERE id = ?",
                statement -> statement.setString(1, id));
    }

    @Override
    public List<AgentToolAttempt> listToolAttempts(String runId) {
        String sql = "SELECT * FROM agent_tool_attempt WHERE run_id = ? ORDER BY prepared_at ASC, id ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentToolAttempt> result = new ArrayList<>();
                while (resultSet.next()) result.add(readToolAttempt(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list tool attempts", exception);
        }
    }

    @Override
    public AgentToolAttempt updateToolAttempt(AgentToolAttempt attempt, long expectedRevision) {
        String sql = """
                UPDATE agent_tool_attempt SET status = ?, result_content = ?, error_message = ?,
                    executing_at = ?, completed_at = ?, revision = ? WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attempt.getStatus().name());
            statement.setString(2, attempt.getResultContent());
            statement.setString(3, attempt.getErrorMessage());
            setDate(statement, 4, attempt.getExecutingAt());
            setDate(statement, 5, attempt.getCompletedAt());
            statement.setLong(6, attempt.getRevision());
            statement.setString(7, attempt.getId());
            statement.setLong(8, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("tool attempt revision has changed: " + attempt.getId());
            }
            return getToolAttempt(attempt.getId());
        } catch (SQLException exception) {
            throw storageFailure("update tool attempt", exception);
        }
    }

    @Override
    public AgentArtifactDashboardRef createOrGetArtifactDashboardRef(AgentArtifactDashboardRef reference) {
        String sql = """
                INSERT INTO agent_artifact_dashboard_ref (
                    id, task_id, artifact_id, artifact_version, chart_index, dashboard_id,
                    chart_id, content_mode, published_by, published_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reference.getId());
            statement.setString(2, reference.getTaskId());
            statement.setString(3, reference.getArtifactId());
            statement.setInt(4, reference.getArtifactVersion());
            statement.setInt(5, reference.getChartIndex());
            statement.setLong(6, reference.getDashboardId());
            statement.setLong(7, reference.getChartId());
            statement.setString(8, reference.getContentMode().name());
            statement.setLong(9, reference.getPublishedBy());
            statement.setLong(10, reference.getPublishedAt().getTime());
            statement.executeUpdate();
            return reference;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                AgentArtifactDashboardRef existing = findArtifactDashboardRef(reference);
                if (existing != null) return existing;
            }
            throw storageFailure("create artifact dashboard reference", exception);
        }
    }

    @Override
    public List<AgentArtifactDashboardRef> listArtifactDashboardRefs(String taskId) {
        String sql = """
                SELECT * FROM agent_artifact_dashboard_ref
                WHERE task_id = ? ORDER BY published_at DESC, id ASC
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentArtifactDashboardRef> result = new ArrayList<>();
                while (resultSet.next()) result.add(readArtifactDashboardRef(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list artifact dashboard references", exception);
        }
    }

    @Override
    public AgentArtifactDashboardRef getArtifactDashboardRefByChartId(Long chartId) {
        if (chartId == null) return null;
        String sql = "SELECT * FROM agent_artifact_dashboard_ref WHERE chart_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, chartId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readArtifactDashboardRef(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get artifact dashboard reference", exception);
        }
    }

    @Override
    public AgentGatewayChannel createGatewayChannel(AgentGatewayChannel channel, String tokenHash) {
        String sql = """
                INSERT INTO agent_gateway_channel (
                    id, name, platform, installation_ref, default_agent_id, created_by, token_hash,
                    enabled, archived_at, created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, channel.getId());
            statement.setString(index++, channel.getName());
            statement.setString(index++, channel.getPlatform().name());
            statement.setString(index++, channel.getInstallationRef());
            statement.setString(index++, channel.getDefaultAgentId());
            statement.setLong(index++, channel.getCreatedBy());
            statement.setString(index++, tokenHash);
            statement.setBoolean(index++, Boolean.TRUE.equals(channel.getEnabled()));
            setDate(statement, index++, channel.getArchivedAt());
            statement.setLong(index++, channel.getGmtCreate().getTime());
            statement.setLong(index++, channel.getGmtModified().getTime());
            statement.setLong(index, channel.getRevision());
            statement.executeUpdate();
            return getGatewayChannel(channel.getId());
        } catch (SQLException exception) {
            throw storageFailure("create gateway channel", exception);
        }
    }

    @Override
    public AgentGatewayChannel getGatewayChannel(String channelId) {
        return queryGatewayChannel("SELECT * FROM agent_gateway_channel WHERE id = ?", statement ->
                statement.setString(1, channelId));
    }

    @Override
    public List<AgentGatewayChannel> listGatewayChannels(Long ownerId) {
        String sql = "SELECT * FROM agent_gateway_channel WHERE created_by = ? ORDER BY updated_at DESC, id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentGatewayChannel> result = new ArrayList<>();
                while (resultSet.next()) result.add(readGatewayChannel(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list gateway channels", exception);
        }
    }

    @Override
    public boolean matchesGatewayToken(String channelId, String tokenHash) {
        String sql = """
                SELECT 1 FROM agent_gateway_channel
                WHERE id = ? AND token_hash = ? AND enabled = TRUE AND archived_at IS NULL
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, channelId);
            statement.setString(2, tokenHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw storageFailure("authenticate gateway channel", exception);
        }
    }

    @Override
    public AgentExternalConversationBinding getConversationBinding(String bindingId) {
        String sql = "SELECT * FROM agent_external_conversation_binding WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bindingId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readConversationBinding(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get external conversation binding", exception);
        }
    }

    @Override
    public AgentExternalConversationBinding getConversationBinding(String channelId, String chatId,
                                                                   String threadId) {
        String sql = """
                SELECT * FROM agent_external_conversation_binding
                WHERE channel_id = ? AND chat_id = ? AND thread_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, channelId);
            statement.setString(2, chatId);
            statement.setString(3, threadId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readConversationBinding(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get external conversation binding", exception);
        }
    }

    @Override
    public AgentExternalConversationBinding createConversationBinding(AgentExternalConversationBinding binding) {
        String sql = """
                INSERT INTO agent_external_conversation_binding (
                    id, channel_id, chat_id, thread_id, session_id, archived_at,
                    created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, binding.getId());
            statement.setString(2, binding.getChannelId());
            statement.setString(3, binding.getChatId());
            statement.setString(4, binding.getThreadId());
            statement.setString(5, binding.getSessionId());
            setDate(statement, 6, binding.getArchivedAt());
            statement.setLong(7, binding.getGmtCreate().getTime());
            statement.setLong(8, binding.getGmtModified().getTime());
            statement.setLong(9, binding.getRevision());
            statement.executeUpdate();
            return getConversationBinding(binding.getChannelId(), binding.getChatId(), binding.getThreadId());
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                AgentExternalConversationBinding existing = getConversationBinding(
                        binding.getChannelId(), binding.getChatId(), binding.getThreadId());
                if (existing != null) return existing;
            }
            throw storageFailure("create external conversation binding", exception);
        }
    }

    @Override
    public AgentInboundMessage getInboundMessage(String channelId, String idempotencyKey) {
        String sql = "SELECT * FROM agent_inbound_message WHERE channel_id = ? AND idempotency_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, channelId);
            statement.setString(2, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readInboundMessage(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get inbound message", exception);
        }
    }

    @Override
    public AgentInboundMessage createInboundMessage(AgentInboundMessage message) {
        String sql = """
                INSERT INTO agent_inbound_message (
                    id, channel_id, binding_id, event_id, message_id, idempotency_key, sender_id,
                    sender_display_name, text, mentions_json, attachments_json, agent_id, task_id, received_at,
                    created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, message.getId());
            statement.setString(index++, message.getChannelId());
            statement.setString(index++, message.getBindingId());
            statement.setString(index++, message.getEventId());
            statement.setString(index++, message.getMessageId());
            statement.setString(index++, message.getIdempotencyKey());
            statement.setString(index++, message.getSenderId());
            statement.setString(index++, message.getSenderDisplayName());
            statement.setString(index++, message.getText());
            statement.setString(index++, JSON.toJSONString(message.getMentions()));
            statement.setString(index++, JSON.toJSONString(message.getAttachments()));
            statement.setString(index++, message.getAgentId());
            statement.setString(index++, message.getTaskId());
            statement.setLong(index++, message.getReceivedAt().getTime());
            statement.setLong(index++, message.getGmtCreate().getTime());
            statement.setLong(index++, message.getGmtModified().getTime());
            statement.setLong(index, message.getRevision());
            statement.executeUpdate();
            return getInboundMessage(message.getChannelId(), message.getIdempotencyKey());
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                AgentInboundMessage existing = getInboundMessage(message.getChannelId(), message.getIdempotencyKey());
                if (existing != null) return existing;
            }
            throw storageFailure("create inbound message", exception);
        }
    }

    @Override
    public AgentInboundMessage attachInboundTask(String messageId, String taskId, long expectedRevision) {
        String sql = """
                UPDATE agent_inbound_message SET task_id = ?, updated_at = ?, revision = ?
                WHERE id = ? AND revision = ? AND (task_id IS NULL OR task_id = ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            statement.setString(1, taskId);
            statement.setLong(2, now);
            statement.setLong(3, expectedRevision + 1);
            statement.setString(4, messageId);
            statement.setLong(5, expectedRevision);
            statement.setString(6, taskId);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("inbound message revision has changed: " + messageId);
            }
            return queryInboundMessageById(messageId);
        } catch (SQLException exception) {
            throw storageFailure("attach inbound task", exception);
        }
    }

    @Override
    public List<AgentInboundMessage> listInboundMessagesAwaitingDelivery(String channelId) {
        String sql = """
                SELECT inbound.* FROM agent_inbound_message inbound
                LEFT JOIN agent_delivery_outbox delivery ON delivery.inbound_message_id = inbound.id
                WHERE inbound.channel_id = ? AND inbound.task_id IS NOT NULL AND delivery.id IS NULL
                ORDER BY inbound.created_at, inbound.id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, channelId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentInboundMessage> result = new ArrayList<>();
                while (resultSet.next()) result.add(readInboundMessage(resultSet));
                return result;
            }
        } catch (SQLException exception) {
            throw storageFailure("list inbound messages awaiting delivery", exception);
        }
    }

    @Override
    public AgentDeliveryCommand createOrGetDelivery(AgentDeliveryCommand command) {
        String sql = """
                INSERT INTO agent_delivery_outbox (
                    id, channel_id, inbound_message_id, task_id, run_id, platform, installation_ref,
                    chat_id, thread_id, reply_to_message_id, content, attachment_refs_json, idempotency_key, status,
                    attempt_count, next_attempt_at, lease_expires_at, platform_message_id, last_error,
                    delivered_at, created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDelivery(statement, command);
            statement.executeUpdate();
            return getDelivery(command.getId());
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                AgentDeliveryCommand existing = queryDeliveryByInbound(command.getInboundMessageId());
                if (existing != null) return existing;
            }
            throw storageFailure("create delivery command", exception);
        }
    }

    @Override
    public List<AgentDeliveryCommand> claimDeliveries(String channelId, Date now, Date leaseExpiresAt, int limit) {
        String sql = """
                SELECT * FROM agent_delivery_outbox
                WHERE channel_id = ?
                  AND ((status = 'PENDING' AND next_attempt_at <= ?)
                    OR (status = 'DELIVERING' AND lease_expires_at < ?))
                ORDER BY created_at, id LIMIT ?
                """;
        List<AgentDeliveryCommand> candidates = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, channelId);
            statement.setLong(2, now.getTime());
            statement.setLong(3, now.getTime());
            statement.setInt(4, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) candidates.add(readDelivery(resultSet));
            }
        } catch (SQLException exception) {
            throw storageFailure("find delivery commands", exception);
        }
        List<AgentDeliveryCommand> claimed = new ArrayList<>();
        for (AgentDeliveryCommand candidate : candidates) {
            String update = """
                    UPDATE agent_delivery_outbox
                    SET status = 'DELIVERING', attempt_count = attempt_count + 1,
                        lease_expires_at = ?, updated_at = ?, revision = revision + 1
                    WHERE id = ? AND revision = ?
                      AND ((status = 'PENDING' AND next_attempt_at <= ?)
                        OR (status = 'DELIVERING' AND lease_expires_at < ?))
                    """;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setLong(1, leaseExpiresAt.getTime());
                statement.setLong(2, now.getTime());
                statement.setString(3, candidate.getId());
                statement.setLong(4, candidate.getRevision());
                statement.setLong(5, now.getTime());
                statement.setLong(6, now.getTime());
                if (statement.executeUpdate() == 1) claimed.add(getDelivery(candidate.getId()));
            } catch (SQLException exception) {
                throw storageFailure("claim delivery command", exception);
            }
        }
        return claimed;
    }

    @Override
    public AgentDeliveryCommand getDelivery(String deliveryId) {
        return queryDelivery("SELECT * FROM agent_delivery_outbox WHERE id = ?", statement ->
                statement.setString(1, deliveryId));
    }

    @Override
    public AgentDeliveryCommand updateDelivery(AgentDeliveryCommand command, long expectedRevision) {
        String sql = """
                UPDATE agent_delivery_outbox SET status = ?, attempt_count = ?, next_attempt_at = ?,
                    lease_expires_at = ?, platform_message_id = ?, last_error = ?, delivered_at = ?,
                    updated_at = ?, revision = ? WHERE id = ? AND revision = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, command.getStatus().name());
            statement.setInt(index++, command.getAttemptCount());
            statement.setLong(index++, command.getNextAttemptAt().getTime());
            setDate(statement, index++, command.getLeaseExpiresAt());
            statement.setString(index++, command.getPlatformMessageId());
            statement.setString(index++, command.getLastError());
            setDate(statement, index++, command.getDeliveredAt());
            statement.setLong(index++, command.getGmtModified().getTime());
            statement.setLong(index++, command.getRevision());
            statement.setString(index++, command.getId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException("delivery revision has changed: " + command.getId());
            }
            return getDelivery(command.getId());
        } catch (SQLException exception) {
            throw storageFailure("update delivery command", exception);
        }
    }

    private AgentArtifactDashboardRef findArtifactDashboardRef(AgentArtifactDashboardRef reference) {
        String sql = """
                SELECT * FROM agent_artifact_dashboard_ref
                WHERE artifact_id = ? AND artifact_version = ? AND chart_index = ?
                    AND dashboard_id = ? AND content_mode = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reference.getArtifactId());
            statement.setInt(2, reference.getArtifactVersion());
            statement.setInt(3, reference.getChartIndex());
            statement.setLong(4, reference.getDashboardId());
            statement.setString(5, reference.getContentMode().name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readArtifactDashboardRef(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("find artifact dashboard reference", exception);
        }
    }

    private AgentRunEvent getRunEvent(long sequence) {
        return getRunEvent("SELECT * FROM agent_run_event WHERE event_order = ?", statement ->
                statement.setLong(1, sequence));
    }

    private AgentRunEvent getRunEvent(String eventId) {
        return getRunEvent("SELECT * FROM agent_run_event WHERE event_id = ?", statement ->
                statement.setString(1, eventId));
    }

    private AgentRunEvent queryRunEvent(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_run_event WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRunEvent(resultSet) : null;
            }
        }
    }

    private void requireMatchingRuntimeEvent(AgentRunEvent existing, AgentRunEvent requested,
                                             int leaseAttempt, long runtimeSequence) {
        if (!existing.getRunId().equals(requested.getRunId())
                || existing.getRuntimeAttempt() == null
                || existing.getRuntimeAttempt() != leaseAttempt
                || existing.getRuntimeSequence() == null
                || existing.getRuntimeSequence() != runtimeSequence) {
            throw new IllegalStateException("runtime event id was already used with different fencing data");
        }
    }

    private AgentRunEvent getRunEvent(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRunEvent(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get run event", exception);
        }
    }

    private AgentTaskContext getTaskContext(String id) {
        String sql = "SELECT * FROM agent_task_context WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readTaskContext(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("get task context", exception);
        }
    }

    private void insertArtifact(Connection connection, AgentArtifact artifact) throws SQLException {
        String sql = """
                INSERT INTO agent_artifact (
                    id, task_id, artifact_type, title, status, current_version,
                    created_by_run_id, created_by, created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifact.getId());
            statement.setString(2, artifact.getTaskId());
            statement.setString(3, artifact.getType().name());
            statement.setString(4, artifact.getTitle());
            statement.setString(5, artifact.getStatus().name());
            statement.setInt(6, artifact.getCurrentVersion());
            statement.setString(7, artifact.getCreatedByRunId());
            setLong(statement, 8, artifact.getCreatedBy());
            statement.setLong(9, artifact.getGmtCreate().getTime());
            statement.setLong(10, artifact.getGmtModified().getTime());
            statement.setLong(11, artifact.getRevision());
            statement.executeUpdate();
        }
    }

    private void insertArtifactVersion(Connection connection, AgentArtifactVersion version) throws SQLException {
        String sql = """
                INSERT INTO agent_artifact_version (
                    artifact_id, version, content_mode, content_json, content_hash,
                    created_by_run_id, created_at, supersedes_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, version.getArtifactId());
            statement.setInt(2, version.getVersion());
            statement.setString(3, version.getContentMode().name());
            statement.setString(4, JSON.toJSONString(version.getContent()));
            statement.setString(5, version.getContentHash());
            statement.setString(6, version.getCreatedByRunId());
            statement.setLong(7, version.getCreatedAt().getTime());
            if (version.getSupersedesVersion() == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, version.getSupersedesVersion());
            }
            statement.executeUpdate();
        }
    }

    private void insertArtifactEvidence(Connection connection, List<AgentArtifactEvidence> evidence)
            throws SQLException {
        if (evidence == null || evidence.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO agent_artifact_evidence (
                    id, artifact_id, artifact_version, run_id, tool_attempt_id,
                    data_source_id, database_name, schema_name, sql_snapshot,
                    sql_hash, executed_at, row_count, result_snapshot_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (AgentArtifactEvidence item : evidence) {
                statement.setString(1, item.getId());
                statement.setString(2, item.getArtifactId());
                statement.setInt(3, item.getArtifactVersion());
                statement.setString(4, item.getRunId());
                statement.setString(5, item.getToolAttemptId());
                setLong(statement, 6, item.getDataSourceId());
                statement.setString(7, item.getDatabaseName());
                statement.setString(8, item.getSchemaName());
                statement.setString(9, item.getSqlSnapshot());
                statement.setString(10, item.getSqlHash());
                setDate(statement, 11, item.getExecutedAt());
                setLong(statement, 12, item.getRowCount());
                statement.setString(13, item.getResultSnapshotId());
                statement.setLong(14, item.getCreatedAt().getTime());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private AgentArtifact queryArtifact(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readArtifact(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query artifact", exception);
        }
    }

    private AgentArtifactDetail artifactDetail(String artifactId) {
        AgentArtifactDetail detail = new AgentArtifactDetail();
        detail.setArtifact(getArtifact(artifactId));
        detail.setVersions(listArtifactVersions(artifactId));
        detail.setEvidence(listArtifactEvidence(artifactId));
        return detail;
    }

    private void insertSqlProposal(Connection connection, AgentSqlProposal proposal) throws SQLException {
        String sql = """
                INSERT INTO agent_sql_proposal (
                    id, run_id, proposal_version, sql_snapshot, sql_hash, data_source_id,
                    database_name, schema_name, operation_class, risk_level, estimated_impact,
                    status, created_at, updated_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, proposal.getId()); statement.setString(2, proposal.getRunId());
            statement.setInt(3, proposal.getProposalVersion()); statement.setString(4, proposal.getSqlSnapshot());
            statement.setString(5, proposal.getSqlHash()); statement.setLong(6, proposal.getDataSourceId());
            statement.setString(7, proposal.getDatabaseName()); statement.setString(8, proposal.getSchemaName());
            statement.setString(9, proposal.getOperationClass().name());
            statement.setString(10, proposal.getRiskLevel().name());
            statement.setString(11, proposal.getEstimatedImpact()); statement.setString(12, proposal.getStatus().name());
            statement.setLong(13, proposal.getCreatedAt().getTime());
            statement.setLong(14, proposal.getUpdatedAt().getTime()); statement.setLong(15, proposal.getRevision());
            statement.executeUpdate();
        }
    }

    private void insertApproval(Connection connection, AgentApproval approval) throws SQLException {
        String sql = """
                INSERT INTO agent_approval (
                    id, proposal_id, run_id, proposal_version, proposal_hash, status,
                    requested_by, requested_at, decided_by, decided_at, decision, reason, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, approval.getId()); statement.setString(2, approval.getProposalId());
            statement.setString(3, approval.getRunId()); statement.setInt(4, approval.getProposalVersion());
            statement.setString(5, approval.getProposalHash()); statement.setString(6, approval.getStatus().name());
            statement.setString(7, approval.getRequestedBy()); statement.setLong(8, approval.getRequestedAt().getTime());
            setLong(statement, 9, approval.getDecidedBy()); setDate(statement, 10, approval.getDecidedAt());
            statement.setString(11, approval.getDecision() == null ? null : approval.getDecision().name());
            statement.setString(12, approval.getReason()); statement.setLong(13, approval.getRevision());
            statement.executeUpdate();
        }
    }

    private void insertToolAttempt(Connection connection, AgentToolAttempt attempt) throws SQLException {
        String sql = """
                INSERT INTO agent_tool_attempt (
                    id, run_id, proposal_id, proposal_version, tool_call_id, tool_name,
                    status, write_operation, result_content, error_message, prepared_at,
                    executing_at, completed_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attempt.getId()); statement.setString(2, attempt.getRunId());
            statement.setString(3, attempt.getProposalId()); statement.setInt(4, attempt.getProposalVersion());
            statement.setString(5, attempt.getToolCallId()); statement.setString(6, attempt.getToolName());
            statement.setString(7, attempt.getStatus().name());
            statement.setBoolean(8, Boolean.TRUE.equals(attempt.getWriteOperation()));
            statement.setString(9, attempt.getResultContent()); statement.setString(10, attempt.getErrorMessage());
            statement.setLong(11, attempt.getPreparedAt().getTime()); setDate(statement, 12, attempt.getExecutingAt());
            setDate(statement, 13, attempt.getCompletedAt()); statement.setLong(14, attempt.getRevision());
            statement.executeUpdate();
        }
    }

    private AgentSqlProposal querySqlProposal(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readSqlProposal(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query SQL proposal", exception);
        }
    }

    private AgentApproval queryApproval(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readApproval(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query approval", exception);
        }
    }

    private AgentToolAttempt queryToolAttempt(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readToolAttempt(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query tool attempt", exception);
        }
    }

    private AgentToolAttempt findToolAttempt(String runId, Integer version, String toolCallId) {
        return queryToolAttempt("""
                SELECT * FROM agent_tool_attempt
                WHERE run_id = ? AND proposal_version = ? AND tool_call_id = ?
                """, statement -> {
            statement.setString(1, runId); statement.setInt(2, version); statement.setString(3, toolCallId);
        });
    }

    private AgentRuntimeProfile queryRuntimeProfile(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRuntimeProfile(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query runtime profile", exception);
        }
    }

    private AgentTaskSchedule querySchedule(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readSchedule(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query agent task schedule", exception);
        }
    }

    private AgentTaskScheduleExecution queryExecution(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readScheduleExecution(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query agent task schedule execution", exception);
        }
    }

    private AgentTaskScheduleExecution findExecution(String scheduleId, Date plannedAt,
                                                     AgentTaskScheduleExecutionSourceEnum source) {
        return queryExecution("""
                SELECT * FROM agent_task_schedule_execution
                WHERE schedule_id = ? AND planned_at = ? AND source = ?
                """, statement -> {
            statement.setString(1, scheduleId);
            statement.setLong(2, plannedAt.getTime());
            statement.setString(3, source.name());
        });
    }

    private void resolveTaskLinkState(AgentTaskScheduleExecution execution) {
        if (execution == null || execution.getTaskId() == null) return;
        AgentTask task = getTask(execution.getTaskId());
        execution.setTaskLinkState(task == null ? AgentTaskLinkStateEnum.DELETED
                : task.getArchivedAt() == null ? AgentTaskLinkStateEnum.AVAILABLE
                : AgentTaskLinkStateEnum.ARCHIVED);
        if (task == null) return;
        execution.setTaskStatus(task.getStatus());
        AgentRun run = execution.getRunId() == null ? null : getRun(execution.getRunId());
        if (run != null) {
            execution.setRunStatus(run.getStatus());
            execution.setRunFailureReason(run.getFailureReason());
            execution.setResultSummary(run.getResultSummary());
        }
    }

    private AgentGatewayChannel queryGatewayChannel(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readGatewayChannel(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query gateway channel", exception);
        }
    }

    private AgentInboundMessage queryInboundMessageById(String messageId) {
        String sql = "SELECT * FROM agent_inbound_message WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readInboundMessage(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query inbound message", exception);
        }
    }

    private AgentDeliveryCommand queryDeliveryByInbound(String inboundMessageId) {
        return queryDelivery("SELECT * FROM agent_delivery_outbox WHERE inbound_message_id = ?", statement ->
                statement.setString(1, inboundMessageId));
    }

    private AgentDeliveryCommand queryDelivery(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readDelivery(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query delivery command", exception);
        }
    }

    private AgentRuntimeInstance queryRuntimeInstance(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRuntimeInstance(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query runtime instance", exception);
        }
    }

    private AgentRuntimeApproval queryRuntimeApproval(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRuntimeApproval(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw storageFailure("query runtime approval", exception);
        }
    }

    private AgentRuntimeRunLease queryRuntimeRunLease(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_runtime_run_lease WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRuntimeRunLease(resultSet) : null;
            }
        }
    }

    private boolean reconcileExpiredRuntimeRun(String runId, Date reconciledAt) {
        String leaseSql = """
                UPDATE agent_runtime_run_lease
                SET lease_state = ?, released_at = ?, terminal_event_id = ?, revision = revision + 1
                WHERE run_id = ? AND lease_state = 'ACTIVE' AND lease_expires_at < ? AND revision = ?
                """;
        String runSql = """
                UPDATE agent_run
                SET status = ?, updated_at = ?, completed_at = ?, failure_reason = ?, revision = revision + 1
                WHERE id = ? AND revision = ?
                  AND status IN ('DISPATCHED', 'RUNNING', 'WAITING_APPROVAL')
                """;
        String releaseSlotSql = """
                UPDATE agent_runtime_instance
                SET active_runs = CASE WHEN active_runs > 0 THEN active_runs - 1 ELSE 0 END,
                    updated_at = ?, revision = revision + 1
                WHERE id = ?
                """;
        String expireApprovalsSql = """
                UPDATE agent_runtime_approval
                SET status = 'EXPIRED', reason = 'Runtime lease expired', revision = revision + 1
                WHERE run_id = ? AND status = 'PENDING'
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                AgentRuntimeRunLease lease = queryRuntimeRunLease(connection, runId);
                if (lease == null || lease.getState() != AgentRuntimeLeaseStateEnum.ACTIVE
                        || !lease.getLeaseExpiresAt().before(reconciledAt)) {
                    connection.commit();
                    return false;
                }
                AgentRun run = queryRun(connection, runId);
                if (run == null) {
                    throw new IllegalStateException("active lease points to a missing run: " + runId);
                }
                boolean runAlreadyTerminal = terminal(run.getStatus());
                AgentApprovalStatusEnum sqlContinuation = run.getStatus() == AgentRunStatusEnum.WAITING_APPROVAL
                        ? querySqlContinuationStatus(connection, runId) : null;
                AgentRunStatusEnum target = runAlreadyTerminal ? run.getStatus()
                        : lease.getCancelRequestedAt() != null
                        ? AgentRunStatusEnum.CANCELLED
                        : sqlContinuation == AgentApprovalStatusEnum.PENDING
                        ? AgentRunStatusEnum.WAITING_APPROVAL
                        : (sqlContinuation == AgentApprovalStatusEnum.APPROVED
                        || sqlContinuation == AgentApprovalStatusEnum.REJECTED)
                        ? AgentRunStatusEnum.QUEUED
                        : lease.getStartedAt() == null ? AgentRunStatusEnum.QUEUED : AgentRunStatusEnum.UNKNOWN;
                AgentRuntimeLeaseStateEnum reconciledLeaseState = sqlContinuation == null
                        ? AgentRuntimeLeaseStateEnum.EXPIRED : AgentRuntimeLeaseStateEnum.SUSPENDED;
                String eventId = "runtime-lease-expired-" + runId + "-" + lease.getLeaseAttempt();
                try (PreparedStatement statement = connection.prepareStatement(leaseSql)) {
                    statement.setString(1, reconciledLeaseState.name());
                    statement.setLong(2, reconciledAt.getTime());
                    statement.setString(3, eventId);
                    statement.setString(4, runId);
                    statement.setLong(5, reconciledAt.getTime());
                    statement.setLong(6, lease.getRevision());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                String failureReason = target == AgentRunStatusEnum.UNKNOWN
                        ? "External runtime lease expired after the provider acknowledged start"
                        : null;
                if (!runAlreadyTerminal) {
                    try (PreparedStatement statement = connection.prepareStatement(runSql)) {
                        statement.setString(1, target.name());
                        statement.setLong(2, reconciledAt.getTime());
                        setDate(statement, 3, target == AgentRunStatusEnum.QUEUED
                                || target == AgentRunStatusEnum.WAITING_APPROVAL ? null : reconciledAt);
                        statement.setString(4, failureReason);
                        statement.setString(5, runId);
                        statement.setLong(6, run.getRevision());
                        if (statement.executeUpdate() != 1) {
                            throw new ConcurrentModificationException(
                                    "runtime run changed during lease reconciliation: " + runId);
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(releaseSlotSql)) {
                    statement.setLong(1, reconciledAt.getTime());
                    statement.setString(2, lease.getRuntimeInstanceId());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "runtime instance slot was not reserved: " + lease.getRuntimeInstanceId());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(expireApprovalsSql)) {
                    statement.setString(1, runId);
                    statement.executeUpdate();
                }
                AgentRunEvent event = new AgentRunEvent();
                event.setEventId(eventId);
                event.setRunId(runId);
                event.setType(ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum.STATUS);
                event.setContent(target.name());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", target.name());
                payload.put("reason", sqlContinuation != null
                        ? "SQL_APPROVAL_CONTINUATION_LEASE_EXPIRED"
                        : runAlreadyTerminal ? "LEASE_EXPIRED_AFTER_RUN_TERMINAL" : "LEASE_EXPIRED");
                payload.put("runtimeAttempt", lease.getLeaseAttempt());
                event.setPayload(payload);
                event.setOccurredAt(reconciledAt);
                event.setPersistedAt(reconciledAt);
                event.setRuntimeAttempt(lease.getLeaseAttempt());
                insertRuntimeRunEvent(connection, event);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (RuntimeException exception) {
                rollbackRuntime(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw storageFailure("reconcile expired runtime run", exception);
        }
    }

    private AgentApprovalStatusEnum querySqlContinuationStatus(Connection connection, String runId)
            throws SQLException {
        String sql = """
                SELECT a.status
                FROM agent_approval a
                JOIN agent_sql_proposal p ON p.id = a.proposal_id
                WHERE a.run_id = ? AND p.status = 'ACTIVE'
                  AND a.status IN ('PENDING', 'APPROVED', 'REJECTED')
                ORDER BY CASE a.status WHEN 'APPROVED' THEN 0 WHEN 'REJECTED' THEN 0 ELSE 1 END,
                         a.requested_at ASC
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? AgentApprovalStatusEnum.valueOf(resultSet.getString("status")) : null;
            }
        }
    }

    private AgentRun queryRun(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_run WHERE id = ?")) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readRun(resultSet) : null;
            }
        }
    }

    private void insertRuntimeRunEvent(Connection connection, AgentRunEvent event) throws SQLException {
        String sql = """
                INSERT INTO agent_run_event (
                    event_id, run_id, event_type, content, payload_json,
                    occurred_at, persisted_at, runtime_attempt, runtime_sequence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.getEventId());
            statement.setString(2, event.getRunId());
            statement.setString(3, event.getType().name());
            statement.setString(4, event.getContent());
            statement.setString(5, JSON.toJSONString(event.getPayload()));
            statement.setLong(6, event.getOccurredAt().getTime());
            statement.setLong(7, event.getPersistedAt().getTime());
            if (event.getRuntimeAttempt() == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, event.getRuntimeAttempt());
            }
            if (event.getRuntimeSequence() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, event.getRuntimeSequence());
            }
            statement.executeUpdate();
        }
    }

    private void requireMatchingTerminalLease(AgentRuntimeRunLease current, AgentRuntimeRunLease requested,
                                              AgentRunEvent event, AgentRunStatusEnum targetStatus) {
        if (!current.getLeaseAttempt().equals(requested.getLeaseAttempt())
                || !current.getRuntimeInstanceId().equals(requested.getRuntimeInstanceId())
                || current.getState() != leaseState(targetStatus)
                || !event.getEventId().equals(current.getTerminalEventId())) {
            throw new IllegalStateException("runtime run already has a different terminal acknowledgement: "
                    + current.getRunId());
        }
    }

    private AgentRuntimeLeaseStateEnum leaseState(AgentRunStatusEnum status) {
        return switch (status) {
            case COMPLETED -> AgentRuntimeLeaseStateEnum.COMPLETED;
            case FAILED -> AgentRuntimeLeaseStateEnum.FAILED;
            case CANCELLED -> AgentRuntimeLeaseStateEnum.CANCELLED;
            case UNKNOWN -> AgentRuntimeLeaseStateEnum.UNKNOWN;
            default -> throw new IllegalArgumentException("run status is not terminal: " + status);
        };
    }

    private boolean terminal(AgentRunStatusEnum status) {
        return status == AgentRunStatusEnum.COMPLETED || status == AgentRunStatusEnum.FAILED
                || status == AgentRunStatusEnum.CANCELLED || status == AgentRunStatusEnum.UNKNOWN;
    }

    private void insertRuntimeRunLease(Connection connection, AgentRuntimeRunLease lease) throws SQLException {
        String sql = """
                INSERT INTO agent_runtime_run_lease (
                    run_id, runtime_instance_id, lease_attempt, lease_token_hash, task_token_hash,
                    claimed_at, lease_expires_at, last_renewed_at, started_at,
                    runtime_execution_id, cancel_requested_at, last_event_sequence, lease_state,
                    released_at, terminal_event_id, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRuntimeRunLease(statement, lease);
            statement.executeUpdate();
        }
    }

    private void replaceRuntimeRunLease(Connection connection, AgentRuntimeRunLease lease,
                                        long expectedRevision) throws SQLException {
        String sql = """
                UPDATE agent_runtime_run_lease SET
                    runtime_instance_id = ?, lease_attempt = ?, lease_token_hash = ?, task_token_hash = ?,
                    claimed_at = ?, lease_expires_at = ?, last_renewed_at = ?, started_at = ?,
                    runtime_execution_id = ?, cancel_requested_at = ?, last_event_sequence = ?,
                    lease_state = ?, released_at = ?, terminal_event_id = ?, revision = ?
                WHERE run_id = ? AND revision = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, lease.getRuntimeInstanceId());
            statement.setInt(index++, lease.getLeaseAttempt());
            statement.setString(index++, lease.getLeaseTokenHash());
            statement.setString(index++, lease.getTaskTokenHash());
            statement.setLong(index++, lease.getClaimedAt().getTime());
            statement.setLong(index++, lease.getLeaseExpiresAt().getTime());
            statement.setLong(index++, lease.getLastRenewedAt().getTime());
            setDate(statement, index++, lease.getStartedAt());
            statement.setString(index++, lease.getRuntimeExecutionId());
            setDate(statement, index++, lease.getCancelRequestedAt());
            statement.setLong(index++, lease.getLastEventSequence());
            statement.setString(index++, lease.getState().name());
            setDate(statement, index++, lease.getReleasedAt());
            statement.setString(index++, lease.getTerminalEventId());
            statement.setLong(index++, lease.getRevision());
            statement.setString(index++, lease.getRunId());
            statement.setLong(index, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new ConcurrentModificationException(
                        "runtime run lease revision has changed: " + lease.getRunId());
            }
        }
    }

    private void bindRuntimeRunLease(PreparedStatement statement, AgentRuntimeRunLease lease) throws SQLException {
        int index = 1;
        statement.setString(index++, lease.getRunId());
        statement.setString(index++, lease.getRuntimeInstanceId());
        statement.setInt(index++, lease.getLeaseAttempt());
        statement.setString(index++, lease.getLeaseTokenHash());
        statement.setString(index++, lease.getTaskTokenHash());
        statement.setLong(index++, lease.getClaimedAt().getTime());
        statement.setLong(index++, lease.getLeaseExpiresAt().getTime());
        statement.setLong(index++, lease.getLastRenewedAt().getTime());
        setDate(statement, index++, lease.getStartedAt());
        statement.setString(index++, lease.getRuntimeExecutionId());
        setDate(statement, index++, lease.getCancelRequestedAt());
        statement.setLong(index++, lease.getLastEventSequence());
        statement.setString(index++, lease.getState().name());
        setDate(statement, index++, lease.getReleasedAt());
        statement.setString(index++, lease.getTerminalEventId());
        statement.setLong(index, lease.getRevision());
    }

    private void bindRuntimeProfile(PreparedStatement statement, AgentRuntimeProfile profile) throws SQLException {
        int index = 1;
        statement.setString(index++, profile.getId());
        statement.setString(index++, profile.getName());
        statement.setString(index++, profile.getTransport().name());
        statement.setString(index++, profile.getProvider().name());
        statement.setString(index++, profile.getExecutable());
        statement.setString(index++, profile.getModel());
        statement.setString(index++, profile.getWorkingDirectoryPolicy());
        statement.setString(index++, JSON.toJSONString(profile.getCustomArguments()));
        statement.setString(index++, JSON.toJSONString(profile.getEnvironmentReferences()));
        statement.setString(index++, profile.getMcpConfiguration());
        statement.setInt(index++, profile.getTimeoutSeconds());
        statement.setInt(index++, profile.getMaxConcurrency());
        statement.setString(index++, profile.getThinkingMode());
        statement.setString(index++, profile.getServiceTier());
        statement.setBoolean(index++, Boolean.TRUE.equals(profile.getSessionResumeEnabled()));
        statement.setBoolean(index++, Boolean.TRUE.equals(profile.getApprovalBridgeEnabled()));
        statement.setBoolean(index++, Boolean.TRUE.equals(profile.getEnabled()));
        setLong(statement, index++, profile.getCreatedBy());
        statement.setLong(index++, profile.getGmtCreate().getTime());
        statement.setLong(index++, profile.getGmtModified().getTime());
        statement.setLong(index, profile.getRevision());
    }

    private void bindRuntimeInstance(PreparedStatement statement, AgentRuntimeInstance instance) throws SQLException {
        int index = 1;
        statement.setString(index++, instance.getId());
        statement.setString(index++, instance.getDaemonId());
        statement.setString(index++, instance.getProvider().name());
        statement.setString(index++, instance.getProviderVersion());
        statement.setString(index++, instance.getProtocolVersion());
        statement.setString(index++, JSON.toJSONString(instance.getCapabilities()));
        statement.setInt(index++, instance.getMaxConcurrency());
        statement.setInt(index++, instance.getActiveRuns());
        statement.setString(index++, instance.getStatus().name());
        statement.setLong(index++, instance.getLastHeartbeatAt().getTime());
        statement.setLong(index++, instance.getRegisteredAt().getTime());
        statement.setLong(index++, instance.getGmtModified().getTime());
        statement.setLong(index, instance.getRevision());
    }

    private void bindAgent(PreparedStatement statement, AgentDefinition agent) throws SQLException {
        int index = 1;
        statement.setString(index++, agent.getId());
        statement.setString(index++, agent.getName());
        statement.setString(index++, agent.getAvatar());
        statement.setString(index++, agent.getDescription());
        statement.setString(index++, agent.getStatus().name());
        statement.setString(index++, agent.getRuntimeType().name());
        statement.setString(index++, agent.getRuntimeProfileId());
        statement.setString(index++, agent.getModelConfigId());
        statement.setString(index++, agent.getSystemPrompt());
        statement.setString(index++, JSON.toJSONString(agent.getCapabilities()));
        statement.setString(index++, JSON.toJSONString(agent.getDataScopes()));
        statement.setString(index++, agent.getOutputContract());
        setLong(statement, index++, agent.getCreatedBy());
        statement.setLong(index++, agent.getGmtCreate().getTime());
        statement.setLong(index++, agent.getGmtModified().getTime());
        statement.setLong(index, agent.getRevision());
    }

    private void bindSchedule(PreparedStatement statement, AgentTaskSchedule schedule) throws SQLException {
        int index = 1;
        statement.setString(index++, schedule.getId());
        statement.setString(index++, schedule.getName());
        statement.setString(index++, schedule.getTaskTitle());
        statement.setString(index++, schedule.getTaskDescription());
        statement.setString(index++, schedule.getAcceptanceCriteria());
        statement.setString(index++, schedule.getAssigneeAgentId());
        statement.setInt(index++, schedule.getPriority());
        statement.setString(index++, JSON.toJSONString(schedule.getDataScopeSnapshot()));
        statement.setString(index++, schedule.getScheduleType().name());
        setDate(statement, index++, schedule.getScheduledAt());
        statement.setString(index++, schedule.getCronExpression());
        statement.setString(index++, schedule.getTimezone());
        statement.setString(index++, schedule.getStatus().name());
        statement.setString(index++, schedule.getConcurrencyPolicy().name());
        statement.setString(index++, schedule.getCatchUpPolicy().name());
        setDate(statement, index++, schedule.getNextRunAt());
        setDate(statement, index++, schedule.getLastRunAt());
        statement.setLong(index++, schedule.getCreatedBy());
        statement.setLong(index++, schedule.getGmtCreate().getTime());
        statement.setLong(index++, schedule.getGmtModified().getTime());
        statement.setLong(index, schedule.getRevision());
    }

    private void bindScheduleExecution(PreparedStatement statement,
                                       AgentTaskScheduleExecution execution) throws SQLException {
        int index = 1;
        statement.setString(index++, execution.getId());
        statement.setString(index++, execution.getScheduleId());
        statement.setString(index++, execution.getSource().name());
        statement.setLong(index++, execution.getPlannedAt().getTime());
        statement.setString(index++, execution.getStatus().name());
        statement.setString(index++, execution.getTaskId());
        statement.setString(index++, execution.getRunId());
        statement.setInt(index++, execution.getAttempt());
        statement.setString(index++, execution.getLeaseToken());
        setDate(statement, index++, execution.getLeaseExpiresAt());
        statement.setString(index++, execution.getReasonCode() == null ? null : execution.getReasonCode().name());
        statement.setString(index++, execution.getFailureReason());
        statement.setLong(index++, execution.getGmtCreate().getTime());
        statement.setLong(index++, execution.getGmtModified().getTime());
        statement.setLong(index, execution.getRevision());
    }

    private void bindTask(PreparedStatement statement, AgentTask task) throws SQLException {
        int index = 1;
        statement.setString(index++, task.getId());
        statement.setString(index++, task.getTitle());
        statement.setString(index++, task.getDescription());
        statement.setString(index++, task.getAcceptanceCriteria());
        statement.setString(index++, task.getStatus().name());
        statement.setInt(index++, task.getPriority());
        statement.setString(index++, task.getAssigneeAgentId());
        setLong(statement, index++, task.getCreatedBy());
        statement.setString(index++, task.getOriginType().name());
        statement.setString(index++, task.getOriginSessionId());
        statement.setString(index++, task.getOriginMessageId());
        statement.setString(index++, task.getOriginScheduleId());
        statement.setString(index++, task.getOriginScheduleExecutionId());
        setDate(statement, index++, task.getPlannedAt());
        statement.setString(index++, JSON.toJSONString(task.getDataScopeSnapshot()));
        setDate(statement, index++, task.getDataScopeSyncedAt());
        setLong(statement, index++, task.getDataScopeSyncedFromAgentRevision());
        statement.setString(index++, task.getCurrentRunId());
        statement.setLong(index++, task.getGmtCreate().getTime());
        statement.setLong(index++, task.getGmtModified().getTime());
        setDate(statement, index++, task.getCompletedAt());
        statement.setLong(index, task.getRevision());
    }

    private void bindRun(PreparedStatement statement, AgentRun run) throws SQLException {
        int index = 1;
        statement.setString(index++, run.getId());
        statement.setString(index++, run.getTaskId());
        statement.setString(index++, run.getAgentId());
        statement.setString(index++, run.getRuntimeType().name());
        statement.setString(index++, run.getRuntimeProfileId());
        statement.setString(index++, run.getRuntimeProvider() == null ? null : run.getRuntimeProvider().name());
        statement.setString(index++, run.getRuntimeProfileSnapshot());
        statement.setString(index++, run.getProviderSessionId());
        statement.setString(index++, run.getTriggerType().name());
        statement.setString(index++, run.getStatus().name());
        statement.setInt(index++, run.getAttempt());
        statement.setString(index++, run.getParentRunId());
        statement.setLong(index++, run.getGmtCreate().getTime());
        statement.setLong(index++, run.getGmtModified().getTime());
        setDate(statement, index++, run.getStartedAt());
        setDate(statement, index++, run.getCompletedAt());
        statement.setString(index++, run.getFailureReason());
        statement.setString(index++, run.getResultSummary());
        statement.setLong(index, run.getRevision());
    }

    private void bindDelivery(PreparedStatement statement, AgentDeliveryCommand command) throws SQLException {
        int index = 1;
        statement.setString(index++, command.getId());
        statement.setString(index++, command.getChannelId());
        statement.setString(index++, command.getInboundMessageId());
        statement.setString(index++, command.getTaskId());
        statement.setString(index++, command.getRunId());
        statement.setString(index++, command.getPlatform().name());
        statement.setString(index++, command.getInstallationRef());
        statement.setString(index++, command.getChatId());
        statement.setString(index++, command.getThreadId());
        statement.setString(index++, command.getReplyToMessageId());
        statement.setString(index++, command.getContent());
        statement.setString(index++, JSON.toJSONString(command.getAttachmentRefs()));
        statement.setString(index++, command.getIdempotencyKey());
        statement.setString(index++, command.getStatus().name());
        statement.setInt(index++, command.getAttemptCount());
        statement.setLong(index++, command.getNextAttemptAt().getTime());
        setDate(statement, index++, command.getLeaseExpiresAt());
        statement.setString(index++, command.getPlatformMessageId());
        statement.setString(index++, command.getLastError());
        setDate(statement, index++, command.getDeliveredAt());
        statement.setLong(index++, command.getGmtCreate().getTime());
        statement.setLong(index++, command.getGmtModified().getTime());
        statement.setLong(index, command.getRevision());
    }

    private AgentGatewayChannel readGatewayChannel(ResultSet resultSet) throws SQLException {
        AgentGatewayChannel channel = new AgentGatewayChannel();
        channel.setId(resultSet.getString("id"));
        channel.setName(resultSet.getString("name"));
        channel.setPlatform(AgentGatewayPlatformEnum.valueOf(resultSet.getString("platform")));
        channel.setInstallationRef(resultSet.getString("installation_ref"));
        channel.setDefaultAgentId(resultSet.getString("default_agent_id"));
        channel.setCreatedBy(resultSet.getLong("created_by"));
        channel.setEnabled(resultSet.getBoolean("enabled"));
        channel.setArchivedAt(getDate(resultSet, "archived_at"));
        channel.setGmtCreate(new Date(resultSet.getLong("created_at")));
        channel.setGmtModified(new Date(resultSet.getLong("updated_at")));
        channel.setRevision(resultSet.getLong("revision"));
        return channel;
    }

    private AgentExternalConversationBinding readConversationBinding(ResultSet resultSet) throws SQLException {
        AgentExternalConversationBinding binding = new AgentExternalConversationBinding();
        binding.setId(resultSet.getString("id"));
        binding.setChannelId(resultSet.getString("channel_id"));
        binding.setChatId(resultSet.getString("chat_id"));
        binding.setThreadId(resultSet.getString("thread_id"));
        binding.setSessionId(resultSet.getString("session_id"));
        binding.setArchivedAt(getDate(resultSet, "archived_at"));
        binding.setGmtCreate(new Date(resultSet.getLong("created_at")));
        binding.setGmtModified(new Date(resultSet.getLong("updated_at")));
        binding.setRevision(resultSet.getLong("revision"));
        return binding;
    }

    private AgentInboundMessage readInboundMessage(ResultSet resultSet) throws SQLException {
        AgentInboundMessage message = new AgentInboundMessage();
        message.setId(resultSet.getString("id"));
        message.setChannelId(resultSet.getString("channel_id"));
        message.setBindingId(resultSet.getString("binding_id"));
        message.setEventId(resultSet.getString("event_id"));
        message.setMessageId(resultSet.getString("message_id"));
        message.setIdempotencyKey(resultSet.getString("idempotency_key"));
        message.setSenderId(resultSet.getString("sender_id"));
        message.setSenderDisplayName(resultSet.getString("sender_display_name"));
        message.setText(resultSet.getString("text"));
        List<String> mentions = JSON.parseObject(resultSet.getString("mentions_json"),
                new TypeReference<List<String>>() { });
        message.setMentions(mentions == null ? new ArrayList<>() : mentions);
        List<ChatAttachment> attachments = JSON.parseObject(resultSet.getString("attachments_json"),
                new TypeReference<List<ChatAttachment>>() { });
        message.setAttachments(attachments == null ? new ArrayList<>() : attachments);
        message.setAgentId(resultSet.getString("agent_id"));
        message.setTaskId(resultSet.getString("task_id"));
        message.setReceivedAt(new Date(resultSet.getLong("received_at")));
        message.setGmtCreate(new Date(resultSet.getLong("created_at")));
        message.setGmtModified(new Date(resultSet.getLong("updated_at")));
        message.setRevision(resultSet.getLong("revision"));
        return message;
    }

    private AgentDeliveryCommand readDelivery(ResultSet resultSet) throws SQLException {
        AgentDeliveryCommand command = new AgentDeliveryCommand();
        command.setId(resultSet.getString("id"));
        command.setChannelId(resultSet.getString("channel_id"));
        command.setInboundMessageId(resultSet.getString("inbound_message_id"));
        command.setTaskId(resultSet.getString("task_id"));
        command.setRunId(resultSet.getString("run_id"));
        command.setPlatform(AgentGatewayPlatformEnum.valueOf(resultSet.getString("platform")));
        command.setInstallationRef(resultSet.getString("installation_ref"));
        command.setChatId(resultSet.getString("chat_id"));
        command.setThreadId(resultSet.getString("thread_id"));
        command.setReplyToMessageId(resultSet.getString("reply_to_message_id"));
        command.setContent(resultSet.getString("content"));
        List<String> attachmentRefs = JSON.parseObject(resultSet.getString("attachment_refs_json"),
                new TypeReference<List<String>>() { });
        command.setAttachmentRefs(attachmentRefs == null ? new ArrayList<>() : attachmentRefs);
        command.setIdempotencyKey(resultSet.getString("idempotency_key"));
        command.setStatus(AgentDeliveryStatusEnum.valueOf(resultSet.getString("status")));
        command.setAttemptCount(resultSet.getInt("attempt_count"));
        command.setNextAttemptAt(new Date(resultSet.getLong("next_attempt_at")));
        command.setLeaseExpiresAt(getDate(resultSet, "lease_expires_at"));
        command.setPlatformMessageId(resultSet.getString("platform_message_id"));
        command.setLastError(resultSet.getString("last_error"));
        command.setDeliveredAt(getDate(resultSet, "delivered_at"));
        command.setGmtCreate(new Date(resultSet.getLong("created_at")));
        command.setGmtModified(new Date(resultSet.getLong("updated_at")));
        command.setRevision(resultSet.getLong("revision"));
        return command;
    }

    private AgentDefinition readAgent(ResultSet resultSet) throws SQLException {
        AgentDefinition agent = new AgentDefinition();
        agent.setId(resultSet.getString("id"));
        agent.setName(resultSet.getString("name"));
        agent.setAvatar(resultSet.getString("avatar"));
        agent.setDescription(resultSet.getString("description"));
        agent.setStatus(AgentStatusEnum.valueOf(resultSet.getString("status")));
        agent.setRuntimeType(AgentRuntimeTypeEnum.valueOf(resultSet.getString("runtime_type")));
        agent.setRuntimeProfileId(resultSet.getString("runtime_profile_id"));
        agent.setModelConfigId(resultSet.getString("model_config_id"));
        agent.setSystemPrompt(resultSet.getString("system_prompt"));
        agent.setCapabilities(readCapabilities(resultSet.getString("capabilities_json")));
        agent.setDataScopes(readScopes(resultSet.getString("data_scopes_json")));
        agent.setOutputContract(resultSet.getString("output_contract"));
        agent.setCreatedBy(getLong(resultSet, "created_by"));
        agent.setGmtCreate(new Date(resultSet.getLong("created_at")));
        agent.setGmtModified(new Date(resultSet.getLong("updated_at")));
        agent.setRevision(resultSet.getLong("revision"));
        return agent;
    }

    private AgentRuntimeProfile readRuntimeProfile(ResultSet resultSet) throws SQLException {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setId(resultSet.getString("id"));
        profile.setName(resultSet.getString("name"));
        profile.setTransport(AgentRuntimeTransportEnum.valueOf(resultSet.getString("transport")));
        profile.setProvider(AgentRuntimeProviderEnum.valueOf(resultSet.getString("provider")));
        profile.setExecutable(resultSet.getString("executable"));
        profile.setModel(resultSet.getString("model"));
        profile.setWorkingDirectoryPolicy(resultSet.getString("working_directory_policy"));
        List<String> arguments = JSON.parseArray(resultSet.getString("custom_arguments_json"), String.class);
        profile.setCustomArguments(arguments == null ? new ArrayList<>() : new ArrayList<>(arguments));
        Map<String, String> environmentReferences = JSON.parseObject(
                resultSet.getString("environment_references_json"),
                new TypeReference<Map<String, String>>() { });
        profile.setEnvironmentReferences(environmentReferences == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(environmentReferences));
        profile.setMcpConfiguration(resultSet.getString("mcp_configuration"));
        profile.setTimeoutSeconds(resultSet.getInt("timeout_seconds"));
        profile.setMaxConcurrency(resultSet.getInt("max_concurrency"));
        profile.setThinkingMode(resultSet.getString("thinking_mode"));
        profile.setServiceTier(resultSet.getString("service_tier"));
        profile.setSessionResumeEnabled(resultSet.getBoolean("session_resume_enabled"));
        profile.setApprovalBridgeEnabled(resultSet.getBoolean("approval_bridge_enabled"));
        profile.setEnabled(resultSet.getBoolean("enabled"));
        profile.setCreatedBy(getLong(resultSet, "created_by"));
        profile.setGmtCreate(new Date(resultSet.getLong("created_at")));
        profile.setGmtModified(new Date(resultSet.getLong("updated_at")));
        profile.setRevision(resultSet.getLong("revision"));
        return profile;
    }

    private AgentRuntimeInstance readRuntimeInstance(ResultSet resultSet) throws SQLException {
        AgentRuntimeInstance instance = new AgentRuntimeInstance();
        instance.setId(resultSet.getString("id"));
        instance.setDaemonId(resultSet.getString("daemon_id"));
        instance.setProvider(AgentRuntimeProviderEnum.valueOf(resultSet.getString("provider")));
        instance.setProviderVersion(resultSet.getString("provider_version"));
        instance.setProtocolVersion(resultSet.getString("protocol_version"));
        LinkedHashSet<String> capabilities = JSON.parseObject(resultSet.getString("capabilities_json"),
                new TypeReference<LinkedHashSet<String>>() { });
        instance.setCapabilities(capabilities == null ? new LinkedHashSet<>() : capabilities);
        instance.setMaxConcurrency(resultSet.getInt("max_concurrency"));
        instance.setActiveRuns(resultSet.getInt("active_runs"));
        instance.setStatus(AgentRuntimeInstanceStatusEnum.valueOf(resultSet.getString("status")));
        instance.setLastHeartbeatAt(new Date(resultSet.getLong("last_heartbeat_at")));
        instance.setRegisteredAt(new Date(resultSet.getLong("registered_at")));
        instance.setGmtModified(new Date(resultSet.getLong("updated_at")));
        instance.setRevision(resultSet.getLong("revision"));
        return instance;
    }

    private AgentRuntimeRunLease readRuntimeRunLease(ResultSet resultSet) throws SQLException {
        AgentRuntimeRunLease lease = new AgentRuntimeRunLease();
        lease.setRunId(resultSet.getString("run_id"));
        lease.setRuntimeInstanceId(resultSet.getString("runtime_instance_id"));
        lease.setLeaseAttempt(resultSet.getInt("lease_attempt"));
        lease.setLeaseTokenHash(resultSet.getString("lease_token_hash"));
        lease.setTaskTokenHash(resultSet.getString("task_token_hash"));
        lease.setClaimedAt(new Date(resultSet.getLong("claimed_at")));
        lease.setLeaseExpiresAt(new Date(resultSet.getLong("lease_expires_at")));
        lease.setLastRenewedAt(new Date(resultSet.getLong("last_renewed_at")));
        lease.setStartedAt(getDate(resultSet, "started_at"));
        lease.setRuntimeExecutionId(resultSet.getString("runtime_execution_id"));
        lease.setCancelRequestedAt(getDate(resultSet, "cancel_requested_at"));
        lease.setLastEventSequence(resultSet.getLong("last_event_sequence"));
        lease.setState(AgentRuntimeLeaseStateEnum.valueOf(resultSet.getString("lease_state")));
        lease.setReleasedAt(getDate(resultSet, "released_at"));
        lease.setTerminalEventId(resultSet.getString("terminal_event_id"));
        lease.setRevision(resultSet.getLong("revision"));
        return lease;
    }

    private AgentRuntimeApproval readRuntimeApproval(ResultSet resultSet) throws SQLException {
        AgentRuntimeApproval approval = new AgentRuntimeApproval();
        approval.setId(resultSet.getString("id"));
        approval.setRunId(resultSet.getString("run_id"));
        approval.setLeaseAttempt(resultSet.getInt("lease_attempt"));
        approval.setProviderRequestId(resultSet.getString("provider_request_id"));
        approval.setToolCallId(resultSet.getString("tool_call_id"));
        approval.setTitle(resultSet.getString("title"));
        Map<String, Object> payload = JSON.parseObject(resultSet.getString("request_payload"),
                new TypeReference<LinkedHashMap<String, Object>>() { });
        approval.setRequestPayload(payload == null ? new LinkedHashMap<>() : payload);
        approval.setAllowOptionId(resultSet.getString("allow_option_id"));
        approval.setRejectOptionId(resultSet.getString("reject_option_id"));
        approval.setStatus(AgentRuntimeApprovalStatusEnum.valueOf(resultSet.getString("status")));
        approval.setRequestedAt(new Date(resultSet.getLong("requested_at")));
        approval.setDecidedBy(getLong(resultSet, "decided_by"));
        approval.setDecidedAt(getDate(resultSet, "decided_at"));
        String decision = resultSet.getString("decision");
        approval.setDecision(decision == null ? null : AgentApprovalDecisionEnum.valueOf(decision));
        approval.setReason(resultSet.getString("reason"));
        approval.setRevision(resultSet.getLong("revision"));
        return approval;
    }

    private AgentTask readTask(ResultSet resultSet) throws SQLException {
        AgentTask task = new AgentTask();
        task.setId(resultSet.getString("id"));
        task.setTitle(resultSet.getString("title"));
        task.setDescription(resultSet.getString("description"));
        task.setAcceptanceCriteria(resultSet.getString("acceptance_criteria"));
        task.setStatus(AgentTaskStatusEnum.valueOf(resultSet.getString("status")));
        task.setPriority(resultSet.getInt("priority"));
        task.setAssigneeAgentId(resultSet.getString("assignee_agent_id"));
        task.setCreatedBy(getLong(resultSet, "created_by"));
        task.setOriginType(AgentTaskOriginTypeEnum.valueOf(resultSet.getString("origin_type")));
        task.setOriginSessionId(resultSet.getString("origin_session_id"));
        task.setOriginMessageId(resultSet.getString("origin_message_id"));
        task.setOriginScheduleId(resultSet.getString("origin_schedule_id"));
        task.setOriginScheduleExecutionId(resultSet.getString("origin_schedule_execution_id"));
        task.setPlannedAt(getDate(resultSet, "planned_at"));
        task.setDataScopeSnapshot(readScopes(resultSet.getString("data_scope_snapshot_json")));
        task.setDataScopeSyncedAt(getDate(resultSet, "data_scope_synced_at"));
        task.setDataScopeSyncedFromAgentRevision(getLong(resultSet, "data_scope_synced_from_agent_revision"));
        task.setCurrentRunId(resultSet.getString("current_run_id"));
        task.setGmtCreate(new Date(resultSet.getLong("created_at")));
        task.setGmtModified(new Date(resultSet.getLong("updated_at")));
        task.setCompletedAt(getDate(resultSet, "completed_at"));
        task.setArchivedAt(getDate(resultSet, "archived_at"));
        task.setRevision(resultSet.getLong("revision"));
        return task;
    }

    private AgentTaskSchedule readSchedule(ResultSet resultSet) throws SQLException {
        AgentTaskSchedule schedule = new AgentTaskSchedule();
        schedule.setId(resultSet.getString("id"));
        schedule.setName(resultSet.getString("name"));
        schedule.setTaskTitle(resultSet.getString("task_title"));
        schedule.setTaskDescription(resultSet.getString("task_description"));
        schedule.setAcceptanceCriteria(resultSet.getString("acceptance_criteria"));
        schedule.setAssigneeAgentId(resultSet.getString("assignee_agent_id"));
        schedule.setPriority(resultSet.getInt("priority"));
        schedule.setDataScopeSnapshot(readScopes(resultSet.getString("data_scope_snapshot_json")));
        schedule.setScheduleType(AgentTaskScheduleTypeEnum.valueOf(resultSet.getString("schedule_type")));
        schedule.setScheduledAt(getDate(resultSet, "scheduled_at"));
        schedule.setCronExpression(resultSet.getString("cron_expression"));
        schedule.setTimezone(resultSet.getString("timezone"));
        schedule.setStatus(AgentTaskScheduleStatusEnum.valueOf(resultSet.getString("status")));
        schedule.setConcurrencyPolicy(AgentTaskScheduleConcurrencyPolicyEnum.valueOf(
                resultSet.getString("concurrency_policy")));
        schedule.setCatchUpPolicy(AgentTaskScheduleCatchUpPolicyEnum.valueOf(
                resultSet.getString("catch_up_policy")));
        schedule.setNextRunAt(getDate(resultSet, "next_run_at"));
        schedule.setLastRunAt(getDate(resultSet, "last_run_at"));
        schedule.setCreatedBy(getLong(resultSet, "created_by"));
        schedule.setGmtCreate(new Date(resultSet.getLong("created_at")));
        schedule.setGmtModified(new Date(resultSet.getLong("updated_at")));
        schedule.setRevision(resultSet.getLong("revision"));
        return schedule;
    }

    private AgentTaskScheduleExecution readScheduleExecution(ResultSet resultSet) throws SQLException {
        AgentTaskScheduleExecution execution = new AgentTaskScheduleExecution();
        execution.setId(resultSet.getString("id"));
        execution.setScheduleId(resultSet.getString("schedule_id"));
        execution.setSource(AgentTaskScheduleExecutionSourceEnum.valueOf(resultSet.getString("source")));
        execution.setPlannedAt(new Date(resultSet.getLong("planned_at")));
        execution.setStatus(AgentTaskScheduleExecutionStatusEnum.valueOf(resultSet.getString("status")));
        execution.setTaskId(resultSet.getString("task_id"));
        execution.setRunId(resultSet.getString("run_id"));
        execution.setAttempt(resultSet.getInt("attempt"));
        execution.setLeaseToken(resultSet.getString("lease_token"));
        execution.setLeaseExpiresAt(getDate(resultSet, "lease_expires_at"));
        String reason = resultSet.getString("reason_code");
        execution.setReasonCode(reason == null ? null : AgentTaskScheduleReasonCodeEnum.valueOf(reason));
        execution.setFailureReason(resultSet.getString("failure_reason"));
        execution.setGmtCreate(new Date(resultSet.getLong("created_at")));
        execution.setGmtModified(new Date(resultSet.getLong("updated_at")));
        execution.setRevision(resultSet.getLong("revision"));
        return execution;
    }

    private AgentRun readRun(ResultSet resultSet) throws SQLException {
        AgentRun run = new AgentRun();
        run.setId(resultSet.getString("id"));
        run.setTaskId(resultSet.getString("task_id"));
        run.setAgentId(resultSet.getString("agent_id"));
        run.setRuntimeType(AgentRuntimeTypeEnum.valueOf(resultSet.getString("runtime_type")));
        run.setRuntimeProfileId(resultSet.getString("runtime_profile_id"));
        String runtimeProvider = resultSet.getString("runtime_provider");
        run.setRuntimeProvider(runtimeProvider == null ? null : AgentRuntimeProviderEnum.valueOf(runtimeProvider));
        run.setRuntimeProfileSnapshot(resultSet.getString("runtime_profile_snapshot"));
        run.setProviderSessionId(resultSet.getString("provider_session_id"));
        run.setTriggerType(AgentRunTriggerTypeEnum.valueOf(resultSet.getString("trigger_type")));
        run.setStatus(AgentRunStatusEnum.valueOf(resultSet.getString("status")));
        run.setAttempt(resultSet.getInt("attempt"));
        run.setParentRunId(resultSet.getString("parent_run_id"));
        run.setGmtCreate(new Date(resultSet.getLong("created_at")));
        run.setGmtModified(new Date(resultSet.getLong("updated_at")));
        run.setStartedAt(getDate(resultSet, "started_at"));
        run.setCompletedAt(getDate(resultSet, "completed_at"));
        run.setFailureReason(resultSet.getString("failure_reason"));
        run.setResultSummary(resultSet.getString("result_summary"));
        run.setRevision(resultSet.getLong("revision"));
        return run;
    }

    private AgentRunEvent readRunEvent(ResultSet resultSet) throws SQLException {
        AgentRunEvent event = new AgentRunEvent();
        event.setSequence(resultSet.getLong("event_order"));
        event.setRuntimeAttempt(getInteger(resultSet, "runtime_attempt"));
        event.setRuntimeSequence(getLong(resultSet, "runtime_sequence"));
        event.setEventId(resultSet.getString("event_id"));
        event.setRunId(resultSet.getString("run_id"));
        event.setType(ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum.valueOf(
                resultSet.getString("event_type")));
        event.setContent(resultSet.getString("content"));
        Map<String, Object> payload = JSON.parseObject(resultSet.getString("payload_json"),
                new TypeReference<Map<String, Object>>() { });
        event.setPayload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload));
        event.setOccurredAt(new Date(resultSet.getLong("occurred_at")));
        event.setPersistedAt(new Date(resultSet.getLong("persisted_at")));
        return event;
    }

    private AgentTaskContext readTaskContext(ResultSet resultSet) throws SQLException {
        AgentTaskContext context = new AgentTaskContext();
        context.setId(resultSet.getString("id"));
        context.setTaskId(resultSet.getString("task_id"));
        context.setType(AgentTaskContextTypeEnum.valueOf(resultSet.getString("context_type")));
        context.setTitle(resultSet.getString("title"));
        context.setContent(resultSet.getString("content"));
        context.setAttachmentName(resultSet.getString("attachment_name"));
        context.setAttachmentMimeType(resultSet.getString("attachment_mime_type"));
        context.setAttachmentSize(getLong(resultSet, "attachment_size"));
        context.setCreatedBy(getLong(resultSet, "created_by"));
        context.setCreatedAt(new Date(resultSet.getLong("created_at")));
        return context;
    }

    private AgentArtifact readArtifact(ResultSet resultSet) throws SQLException {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setId(resultSet.getString("id"));
        artifact.setTaskId(resultSet.getString("task_id"));
        artifact.setType(AgentArtifactTypeEnum.valueOf(resultSet.getString("artifact_type")));
        artifact.setTitle(resultSet.getString("title"));
        artifact.setStatus(AgentArtifactStatusEnum.valueOf(resultSet.getString("status")));
        artifact.setCurrentVersion(resultSet.getInt("current_version"));
        artifact.setCreatedByRunId(resultSet.getString("created_by_run_id"));
        artifact.setCreatedBy(getLong(resultSet, "created_by"));
        artifact.setGmtCreate(new Date(resultSet.getLong("created_at")));
        artifact.setGmtModified(new Date(resultSet.getLong("updated_at")));
        artifact.setRevision(resultSet.getLong("revision"));
        return artifact;
    }

    private AgentArtifactVersion readArtifactVersion(ResultSet resultSet) throws SQLException {
        AgentArtifactVersion version = new AgentArtifactVersion();
        version.setArtifactId(resultSet.getString("artifact_id"));
        version.setVersion(resultSet.getInt("version"));
        version.setContentMode(AgentArtifactContentModeEnum.valueOf(resultSet.getString("content_mode")));
        Map<String, Object> content = JSON.parseObject(resultSet.getString("content_json"),
                new TypeReference<Map<String, Object>>() { });
        version.setContent(content == null ? new LinkedHashMap<>() : new LinkedHashMap<>(content));
        version.setContentHash(resultSet.getString("content_hash"));
        version.setCreatedByRunId(resultSet.getString("created_by_run_id"));
        version.setCreatedAt(new Date(resultSet.getLong("created_at")));
        int supersedes = resultSet.getInt("supersedes_version");
        version.setSupersedesVersion(resultSet.wasNull() ? null : supersedes);
        return version;
    }

    private AgentArtifactEvidence readArtifactEvidence(ResultSet resultSet) throws SQLException {
        AgentArtifactEvidence evidence = new AgentArtifactEvidence();
        evidence.setId(resultSet.getString("id"));
        evidence.setArtifactId(resultSet.getString("artifact_id"));
        evidence.setArtifactVersion(resultSet.getInt("artifact_version"));
        evidence.setRunId(resultSet.getString("run_id"));
        evidence.setToolAttemptId(resultSet.getString("tool_attempt_id"));
        evidence.setDataSourceId(getLong(resultSet, "data_source_id"));
        evidence.setDatabaseName(resultSet.getString("database_name"));
        evidence.setSchemaName(resultSet.getString("schema_name"));
        evidence.setSqlSnapshot(resultSet.getString("sql_snapshot"));
        evidence.setSqlHash(resultSet.getString("sql_hash"));
        evidence.setExecutedAt(getDate(resultSet, "executed_at"));
        evidence.setRowCount(getLong(resultSet, "row_count"));
        evidence.setResultSnapshotId(resultSet.getString("result_snapshot_id"));
        evidence.setCreatedAt(new Date(resultSet.getLong("created_at")));
        return evidence;
    }

    private AgentSqlProposal readSqlProposal(ResultSet resultSet) throws SQLException {
        AgentSqlProposal proposal = new AgentSqlProposal();
        proposal.setId(resultSet.getString("id")); proposal.setRunId(resultSet.getString("run_id"));
        proposal.setProposalVersion(resultSet.getInt("proposal_version"));
        proposal.setSqlSnapshot(resultSet.getString("sql_snapshot"));
        proposal.setSqlHash(resultSet.getString("sql_hash"));
        proposal.setDataSourceId(resultSet.getLong("data_source_id"));
        proposal.setDatabaseName(resultSet.getString("database_name"));
        proposal.setSchemaName(resultSet.getString("schema_name"));
        proposal.setOperationClass(AgentSqlOperationClassEnum.valueOf(resultSet.getString("operation_class")));
        proposal.setRiskLevel(AgentRiskLevelEnum.valueOf(resultSet.getString("risk_level")));
        proposal.setEstimatedImpact(resultSet.getString("estimated_impact"));
        proposal.setStatus(AgentSqlProposalStatusEnum.valueOf(resultSet.getString("status")));
        proposal.setCreatedAt(new Date(resultSet.getLong("created_at")));
        proposal.setUpdatedAt(new Date(resultSet.getLong("updated_at")));
        proposal.setRevision(resultSet.getLong("revision"));
        return proposal;
    }

    private AgentApproval readApproval(ResultSet resultSet) throws SQLException {
        AgentApproval approval = new AgentApproval();
        approval.setId(resultSet.getString("id")); approval.setProposalId(resultSet.getString("proposal_id"));
        approval.setRunId(resultSet.getString("run_id"));
        approval.setProposalVersion(resultSet.getInt("proposal_version"));
        approval.setProposalHash(resultSet.getString("proposal_hash"));
        approval.setStatus(AgentApprovalStatusEnum.valueOf(resultSet.getString("status")));
        approval.setRequestedBy(resultSet.getString("requested_by"));
        approval.setRequestedAt(new Date(resultSet.getLong("requested_at")));
        approval.setDecidedBy(getLong(resultSet, "decided_by"));
        approval.setDecidedAt(getDate(resultSet, "decided_at"));
        String decision = resultSet.getString("decision");
        approval.setDecision(decision == null ? null : AgentApprovalDecisionEnum.valueOf(decision));
        approval.setReason(resultSet.getString("reason")); approval.setRevision(resultSet.getLong("revision"));
        return approval;
    }

    private AgentToolAttempt readToolAttempt(ResultSet resultSet) throws SQLException {
        AgentToolAttempt attempt = new AgentToolAttempt();
        attempt.setId(resultSet.getString("id")); attempt.setRunId(resultSet.getString("run_id"));
        attempt.setProposalId(resultSet.getString("proposal_id"));
        attempt.setProposalVersion(resultSet.getInt("proposal_version"));
        attempt.setToolCallId(resultSet.getString("tool_call_id")); attempt.setToolName(resultSet.getString("tool_name"));
        attempt.setStatus(AgentToolAttemptStatusEnum.valueOf(resultSet.getString("status")));
        attempt.setWriteOperation(resultSet.getBoolean("write_operation"));
        attempt.setResultContent(resultSet.getString("result_content"));
        attempt.setErrorMessage(resultSet.getString("error_message"));
        attempt.setPreparedAt(new Date(resultSet.getLong("prepared_at")));
        attempt.setExecutingAt(getDate(resultSet, "executing_at"));
        attempt.setCompletedAt(getDate(resultSet, "completed_at"));
        attempt.setRevision(resultSet.getLong("revision"));
        return attempt;
    }

    private AgentArtifactDashboardRef readArtifactDashboardRef(ResultSet resultSet) throws SQLException {
        AgentArtifactDashboardRef reference = new AgentArtifactDashboardRef();
        reference.setId(resultSet.getString("id"));
        reference.setTaskId(resultSet.getString("task_id"));
        reference.setArtifactId(resultSet.getString("artifact_id"));
        reference.setArtifactVersion(resultSet.getInt("artifact_version"));
        reference.setChartIndex(resultSet.getInt("chart_index"));
        reference.setDashboardId(resultSet.getLong("dashboard_id"));
        reference.setChartId(resultSet.getLong("chart_id"));
        reference.setContentMode(AgentArtifactContentModeEnum.valueOf(resultSet.getString("content_mode")));
        reference.setPublishedBy(resultSet.getLong("published_by"));
        reference.setPublishedAt(new Date(resultSet.getLong("published_at")));
        return reference;
    }

    private LinkedHashSet<AgentCapabilityEnum> readCapabilities(String json) {
        LinkedHashSet<AgentCapabilityEnum> result = JSON.parseObject(
                json, new TypeReference<LinkedHashSet<AgentCapabilityEnum>>() { });
        return result == null ? new LinkedHashSet<>() : result;
    }

    private List<AgentDataScope> readScopes(String json) {
        List<AgentDataScope> result = JSON.parseObject(
                json, new TypeReference<List<AgentDataScope>>() { });
        return result == null ? new ArrayList<>() : result;
    }

    static DataSource createDataSource(Path databasePath) {
        try {
            Path normalized = databasePath.toAbsolutePath().normalize();
            Files.createDirectories(normalized.getParent());
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:file:" + normalized
                    + ";DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize agent control database", exception);
        }
    }

    private static Path defaultDatabasePath() {
        return Path.of(ConfigUtils.getEnvBasePath(), "storage", "agent", DATABASE_NAME);
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Long getLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void setDate(PreparedStatement statement, int index, Date value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value.getTime());
        }
    }

    private static Date getDate(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : new Date(value);
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void rollbackRuntime(Connection connection, RuntimeException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static IllegalStateException storageFailure(String operation, SQLException exception) {
        return new IllegalStateException("failed to " + operation + " in agent control store", exception);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private record RunRevision(String runId, long revision) {
    }
}
