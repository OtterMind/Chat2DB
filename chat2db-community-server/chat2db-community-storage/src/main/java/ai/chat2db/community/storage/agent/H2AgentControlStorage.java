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
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
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

@Component
public class H2AgentControlStorage implements IAgentControlStorage {

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
    public AgentTaskCreation createTaskWithInitialRun(AgentTask task, AgentRun run) {
        String taskSql = """
                INSERT INTO agent_task (
                    id, title, description, acceptance_criteria, status, priority,
                    assignee_agent_id, created_by, origin_type, origin_session_id,
                    origin_message_id, data_scope_snapshot_json, data_scope_synced_at,
                    data_scope_synced_from_agent_revision, current_run_id,
                    created_at, updated_at, completed_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String runSql = """
                INSERT INTO agent_run (
                    id, task_id, agent_id, runtime_type, runtime_profile_snapshot,
                    trigger_type, status, attempt, parent_run_id, created_at,
                    updated_at, started_at, completed_at, failure_reason,
                    result_summary, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    id, task_id, agent_id, runtime_type, runtime_profile_snapshot,
                    trigger_type, status, attempt, parent_run_id, created_at,
                    updated_at, started_at, completed_at, failure_reason,
                    result_summary, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        statement.setString(index++, run.getRuntimeProfileSnapshot());
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

    private AgentRun readRun(ResultSet resultSet) throws SQLException {
        AgentRun run = new AgentRun();
        run.setId(resultSet.getString("id"));
        run.setTaskId(resultSet.getString("task_id"));
        run.setAgentId(resultSet.getString("agent_id"));
        run.setRuntimeType(AgentRuntimeTypeEnum.valueOf(resultSet.getString("runtime_type")));
        run.setRuntimeProfileSnapshot(resultSet.getString("runtime_profile_snapshot"));
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

    private static IllegalStateException storageFailure(String operation, SQLException exception) {
        return new IllegalStateException("failed to " + operation + " in agent control store", exception);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
