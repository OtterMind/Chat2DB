package ai.chat2db.community.storage.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutput;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutputKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderLease;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSaga;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSagaState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportBeginDecision;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportItemAck;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecution;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecutionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartDecision;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartResult;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.tools.util.ConfigUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * H2 control-plane ledger for subscription-backed AI. This class deliberately
 * stores no provider credential or API key material.
 */
public final class H2AiSubscriptionStateRepository implements IAiSubscriptionStateRepository {

    private static final int SCHEMA_VERSION = 3;
    private static final String SECRET_IMPORT_ATTEMPT_ITEM = "__attempt__";

    private final String jdbcUrl;

    public H2AiSubscriptionStateRepository(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    }

    public static H2AiSubscriptionStateRepository forCommunityProfile() {
        Path directory = Path.of(ConfigUtils.getEnvBasePath(), "storage", "ai-state").toAbsolutePath();
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the AI state ledger directory", exception);
        }
        String url = "jdbc:h2:file:" + directory.resolve("subscription-ledger")
                + ";DB_CLOSE_ON_EXIT=FALSE";
        return new H2AiSubscriptionStateRepository(url);
    }

    @Override
    public synchronized void initialize() {
        inTransaction(connection -> {
            rejectFutureSchemaVersion(connection);
            for (String statement : schemaStatements()) {
                try (Statement sql = connection.createStatement()) {
                    sql.execute(statement);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO subscription_ai_schema_version(version, installed_at) "
                            + "SELECT ?, CURRENT_TIMESTAMP WHERE NOT EXISTS "
                            + "(SELECT 1 FROM subscription_ai_schema_version WHERE version = ?)")) {
                statement.setInt(1, SCHEMA_VERSION);
                statement.setInt(2, SCHEMA_VERSION);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_secret_import SET state = 'EXPIRED', updated_at = CURRENT_TIMESTAMP "
                            + "WHERE expires_at < CURRENT_TIMESTAMP AND state IN ('ATTEMPT_STARTED', 'ITEM_PENDING')")) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void rejectFutureSchemaVersion(Connection connection) throws SQLException {
        boolean versionTableExists;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'PUBLIC' "
                        + "AND table_name = 'SUBSCRIPTION_AI_SCHEMA_VERSION'");
             ResultSet resultSet = statement.executeQuery()) {
            versionTableExists = resultSet.next();
        }
        if (!versionTableExists) {
            return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT MAX(version) FROM subscription_ai_schema_version")) {
            if (resultSet.next() && resultSet.getObject(1) != null
                    && resultSet.getInt(1) > SCHEMA_VERSION) {
                throw new IllegalStateException("AI state ledger schema is newer than this binary");
            }
        }
    }

    @Override
    public int schemaVersion() {
        return inConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT MAX(version) FROM subscription_ai_schema_version")) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        });
    }

    public List<String> applicationTables() {
        return inConnection(connection -> {
            List<String> tables = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'PUBLIC' AND table_name LIKE 'AI\\_%' ESCAPE '\\' "
                            + "ORDER BY table_name");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString(1));
                }
            }
            return tables;
        });
    }

    public boolean schemaContainsColumnMatching(String sqlPattern) {
        return inConnection(connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(null, "PUBLIC", null, sqlPattern)) {
                return columns.next();
            }
        });
    }

    @Override
    public AiProviderConnection connection(AiProviderEnum provider) {
        return inTransaction(connection -> {
            ensureProvider(connection, provider);
            return readConnection(connection, provider);
        });
    }

    @Override
    public void transitionConnection(AiProviderEnum provider, AiProviderConnectionState expected,
                                     AiProviderConnectionState target, String maskedAccount) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        inTransaction(connection -> {
            ensureProvider(connection, provider);
            AiProviderConnection current = readConnectionForUpdate(connection, provider);
            if (current.state() != expected) {
                throw new IllegalStateException("Provider connection changed concurrently");
            }
            if (!expected.canTransitionTo(target)) {
                throw new IllegalStateException("Illegal provider connection transition: "
                        + expected + " -> " + target);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET state = ?, masked_account = COALESCE(?, masked_account), "
                            + "updated_at = CURRENT_TIMESTAMP WHERE provider = ? AND state = ?")) {
                statement.setString(1, target.name());
                statement.setString(2, maskedAccount);
                statement.setString(3, provider.name());
                statement.setString(4, expected.name());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Provider connection changed concurrently");
                }
            }
            return null;
        });
    }

    @Override
    public void replaceModelSnapshotAndGlobalDefault(AiProviderEnum provider, Instant discoveredAt,
                                                     List<AiModelSnapshot> models, AiModelRef globalDefault) {
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        List<AiModelSnapshot> safeModels = List.copyOf(models);
        validateSnapshot(provider, safeModels, globalDefault);
        inTransaction(connection -> {
            ensureProvider(connection, provider);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM ai_model_snapshot WHERE provider = ?")) {
                delete.setString(1, provider.name());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ai_model_snapshot(provider, model_id, access_type, route_kind, display_name, "
                            + "discovered_at, available, disabled_reason, supported_reasoning_efforts, "
                            + "default_reasoning_effort) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (AiModelSnapshot model : safeModels) {
                    insert.setString(1, provider.name());
                    insert.setString(2, model.modelRef().modelId());
                    insert.setString(3, model.modelRef().accessType().name());
                    insert.setString(4, model.modelRef().routeKind().name());
                    insert.setString(5, model.displayName());
                    insert.setTimestamp(6, Timestamp.from(model.discoveredAt()));
                    insert.setBoolean(7, model.available());
                    insert.setString(8, model.disabledReason());
                    insert.setString(9, encodeReasoningEfforts(model.supportedReasoningEfforts()));
                    insert.setString(10, model.defaultReasoningEffort());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM ai_model_preference WHERE scope_type = 'GLOBAL' AND scope_id = ''")) {
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ai_model_preference(scope_type, scope_id, provider, model_id, access_type, "
                            + "route_kind, updated_at) VALUES('GLOBAL', '', ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                bindModelRef(insert, 1, globalDefault);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET discovered_at = ?, discovery_error_code = NULL, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE provider = ?")) {
                update.setTimestamp(1, Timestamp.from(discoveredAt));
                update.setString(2, provider.name());
                update.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void replaceModelSnapshot(AiProviderEnum provider, Instant discoveredAt,
                                     List<AiModelSnapshot> models) {
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        List<AiModelSnapshot> safeModels = List.copyOf(models);
        for (AiModelSnapshot model : safeModels) {
            if (model.modelRef().provider() != provider) {
                throw new IllegalArgumentException("Snapshot contains a model from another provider");
            }
        }
        inTransaction(connection -> {
            ensureProvider(connection, provider);
            AiProviderConnection current = readConnectionForUpdate(connection, provider);
            if (current.state() != AiProviderConnectionState.CONNECTED
                    && current.state() != AiProviderConnectionState.DISCOVERY_FAILED) {
                throw new IllegalStateException("Provider must be connected before model discovery");
            }
            writeModelSnapshot(connection, provider, safeModels);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET state = ?, discovered_at = ?, "
                            + "discovery_error_code = NULL, updated_at = CURRENT_TIMESTAMP WHERE provider = ?")) {
                update.setString(1, AiProviderConnectionState.CONNECTED.name());
                update.setTimestamp(2, Timestamp.from(discoveredAt));
                update.setString(3, provider.name());
                update.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void markDiscoveryFailed(AiProviderEnum provider, String errorCode) {
        inTransaction(connection -> {
            ensureProvider(connection, provider);
            AiProviderConnection current = readConnectionForUpdate(connection, provider);
            if (current.state() != AiProviderConnectionState.CONNECTED
                    && current.state() != AiProviderConnectionState.DISCOVERY_FAILED) {
                throw new IllegalStateException("Provider must remain connected when discovery fails");
            }
            String safeErrorCode = requireText(errorCode, "errorCode");
            try (PreparedStatement updateConnection = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET state = ?, discovery_error_code = ?, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE provider = ?")) {
                updateConnection.setString(1, AiProviderConnectionState.DISCOVERY_FAILED.name());
                updateConnection.setString(2, safeErrorCode);
                updateConnection.setString(3, provider.name());
                updateConnection.executeUpdate();
            }
            try (PreparedStatement disableModels = connection.prepareStatement(
                    "UPDATE ai_model_snapshot SET available = FALSE, disabled_reason = ? WHERE provider = ?")) {
                disableModels.setString(1, safeErrorCode);
                disableModels.setString(2, provider.name());
                disableModels.executeUpdate();
            }
            try (PreparedStatement clearPreferences = connection.prepareStatement(
                    "DELETE FROM ai_model_preference WHERE provider = ?")) {
                clearPreferences.setString(1, provider.name());
                clearPreferences.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void markModelRejected(AiModelRef modelRef, String errorCode) {
        Objects.requireNonNull(modelRef, "modelRef");
        String safeErrorCode = requireText(errorCode, "errorCode");
        inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ai_model_snapshot SET available = FALSE, disabled_reason = ? "
                            + "WHERE provider = ? AND model_id = ? AND access_type = ? AND route_kind = ?")) {
                update.setString(1, safeErrorCode);
                update.setString(2, modelRef.provider().name());
                update.setString(3, modelRef.modelId());
                update.setString(4, modelRef.accessType().name());
                update.setString(5, modelRef.routeKind().name());
                update.executeUpdate();
            }
            try (PreparedStatement clearPreferences = connection.prepareStatement(
                    "DELETE FROM ai_model_preference WHERE provider = ? AND model_id = ? "
                            + "AND access_type = ? AND route_kind = ?")) {
                clearPreferences.setString(1, modelRef.provider().name());
                clearPreferences.setString(2, modelRef.modelId());
                clearPreferences.setString(3, modelRef.accessType().name());
                clearPreferences.setString(4, modelRef.routeKind().name());
                clearPreferences.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<AiModelSnapshot> listCurrentModels(AiProviderEnum provider) {
        return inConnection(connection -> {
            List<AiModelSnapshot> models = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT model_id, access_type, route_kind, display_name, discovered_at, available, "
                            + "disabled_reason, supported_reasoning_efforts, default_reasoning_effort "
                            + "FROM ai_model_snapshot WHERE provider = ? ORDER BY model_id")) {
                statement.setString(1, provider.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        AiModelRef modelRef = new AiModelRef(
                                AiAccessType.valueOf(resultSet.getString("access_type")),
                                provider,
                                AiRouteKind.valueOf(resultSet.getString("route_kind")),
                                resultSet.getString("model_id"));
                        models.add(new AiModelSnapshot(modelRef,
                                resultSet.getString("display_name"),
                                resultSet.getTimestamp("discovered_at").toInstant(),
                                resultSet.getBoolean("available"),
                                resultSet.getString("disabled_reason"),
                                decodeReasoningEfforts(resultSet.getString("supported_reasoning_efforts")),
                                resultSet.getString("default_reasoning_effort")));
                    }
                }
            }
            return models;
        });
    }

    @Override
    public Optional<AiModelRef> getGlobalDefault() {
        return inConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT provider, model_id, access_type, route_kind FROM ai_model_preference "
                            + "WHERE scope_type = 'GLOBAL' AND scope_id = ''");
                 ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AiModelRef(
                        AiAccessType.valueOf(resultSet.getString("access_type")),
                        AiProviderEnum.valueOf(resultSet.getString("provider")),
                        AiRouteKind.valueOf(resultSet.getString("route_kind")),
                        resultSet.getString("model_id")));
            }
        });
    }

    @Override
    public void setGlobalDefault(AiModelRef modelRef) {
        Objects.requireNonNull(modelRef, "modelRef");
        inTransaction(connection -> {
            try (PreparedStatement available = connection.prepareStatement(
                    "SELECT COUNT(*) FROM ai_model_snapshot WHERE provider = ? AND model_id = ? "
                            + "AND access_type = ? AND route_kind = ? AND available = TRUE")) {
                bindModelRef(available, 1, modelRef);
                try (ResultSet resultSet = available.executeQuery()) {
                    if (!resultSet.next() || resultSet.getInt(1) != 1) {
                        throw new IllegalArgumentException("Global default must be a currently available model");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "MERGE INTO ai_model_preference(scope_type, scope_id, provider, model_id, access_type, "
                            + "route_kind, updated_at) KEY(scope_type, scope_id) "
                            + "VALUES('GLOBAL', '', ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                bindModelRef(statement, 1, modelRef);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void setConversationModel(String conversationId, AiModelRef modelRef) {
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "MERGE INTO ai_model_preference(scope_type, scope_id, provider, model_id, access_type, "
                            + "route_kind, updated_at) KEY(scope_type, scope_id) "
                            + "VALUES('CONVERSATION', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                statement.setString(1, requireText(conversationId, "conversationId"));
                bindModelRef(statement, 2, modelRef);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<AiModelRef> getConversationModel(String conversationId) {
        return inConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT provider, model_id, access_type, route_kind FROM ai_model_preference "
                            + "WHERE scope_type = 'CONVERSATION' AND scope_id = ?")) {
                statement.setString(1, requireText(conversationId, "conversationId"));
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(readModelRef(resultSet)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void createAttempt(String attemptId, String messageId, AiProviderEnum provider, AiAttemptState state) {
        inTransaction(connection -> {
            ensureProvider(connection, provider);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_attempt(attempt_id, message_id, provider, state, created_at, updated_at) "
                            + "VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                statement.setString(1, requireText(attemptId, "attemptId"));
                statement.setString(2, requireText(messageId, "messageId"));
                statement.setString(3, provider.name());
                statement.setString(4, state.name());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean tryCreateAttemptAndAcquireProviderLease(String attemptId, String messageId,
                                                           AiProviderEnum provider, AiAttemptState state,
                                                           long expectedFenceGeneration) {
        return inTransaction(connection -> {
            ensureProvider(connection, provider);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT state, fence_generation, lease_attempt_id FROM ai_provider_connection "
                            + "WHERE provider = ? FOR UPDATE")) {
                select.setString(1, provider.name());
                try (ResultSet resultSet = select.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getLong("fence_generation") != expectedFenceGeneration
                            || resultSet.getString("lease_attempt_id") != null
                            || AiProviderConnectionState.valueOf(resultSet.getString("state"))
                            != AiProviderConnectionState.CONNECTED) {
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ai_attempt(attempt_id, message_id, provider, state, created_at, updated_at) "
                            + "VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                insert.setString(1, requireText(attemptId, "attemptId"));
                insert.setString(2, requireText(messageId, "messageId"));
                insert.setString(3, provider.name());
                insert.setString(4, Objects.requireNonNull(state, "state").name());
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET lease_attempt_id = ?, lease_fence_generation = ?, "
                            + "lease_acquired_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE provider = ?")) {
                update.setString(1, attemptId);
                update.setLong(2, expectedFenceGeneration);
                update.setString(3, provider.name());
                update.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public Optional<AiAttempt> findAttempt(String attemptId) {
        return inConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT message_id, provider, state, external_thread_id, external_turn_id, created_at, "
                            + "updated_at FROM ai_attempt WHERE attempt_id = ?")) {
                statement.setString(1, requireText(attemptId, "attemptId"));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new AiAttempt(
                            attemptId,
                            resultSet.getString("message_id"),
                            AiProviderEnum.valueOf(resultSet.getString("provider")),
                            AiAttemptState.valueOf(resultSet.getString("state")),
                            resultSet.getString("external_thread_id"),
                            resultSet.getString("external_turn_id"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getTimestamp("updated_at").toInstant()));
                }
            }
        });
    }

    @Override
    public List<AiAttempt> listAttemptsByMessageId(String messageId) {
        return inConnection(connection -> {
            List<AiAttempt> attempts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT attempt_id, provider, state, external_thread_id, external_turn_id, created_at, "
                            + "updated_at FROM ai_attempt WHERE message_id = ? ORDER BY created_at DESC")) {
                statement.setString(1, requireText(messageId, "messageId"));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        attempts.add(new AiAttempt(
                                resultSet.getString("attempt_id"), messageId,
                                AiProviderEnum.valueOf(resultSet.getString("provider")),
                                AiAttemptState.valueOf(resultSet.getString("state")),
                                resultSet.getString("external_thread_id"),
                                resultSet.getString("external_turn_id"),
                                resultSet.getTimestamp("created_at").toInstant(),
                                resultSet.getTimestamp("updated_at").toInstant()));
                    }
                }
            }
            return attempts;
        });
    }

    @Override
    public void transitionAttempt(String attemptId, AiAttemptState expected, AiAttemptState target,
                                  String externalThreadId, String externalTurnId) {
        if (!expected.canTransitionTo(target)) {
            throw new IllegalStateException("Illegal AI attempt transition: " + expected + " -> " + target);
        }
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_attempt SET state = ?, external_thread_id = COALESCE(?, external_thread_id), "
                            + "external_turn_id = COALESCE(?, external_turn_id), updated_at = CURRENT_TIMESTAMP "
                            + "WHERE attempt_id = ? AND state = ?")) {
                statement.setString(1, target.name());
                statement.setString(2, externalThreadId);
                statement.setString(3, externalTurnId);
                statement.setString(4, requireText(attemptId, "attemptId"));
                statement.setString(5, expected.name());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("AI attempt changed concurrently or does not exist");
                }
            }
            return null;
        });
    }

    @Override
    public void appendAttemptOutput(String attemptId, long sequence, AiAttemptOutputKind kind,
                                    String content, boolean visible, boolean contextEligible) {
        try {
            inTransaction(connection -> {
                AiAttemptState state;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT state FROM ai_attempt WHERE attempt_id = ? FOR UPDATE")) {
                    lock.setString(1, requireText(attemptId, "attemptId"));
                    try (ResultSet resultSet = lock.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("Attempt does not exist");
                        }
                        state = AiAttemptState.valueOf(resultSet.getString(1));
                    }
                }
                if (state != AiAttemptState.ACTIVE
                        && state != AiAttemptState.OUTPUT_VISIBLE
                        && state != AiAttemptState.TOOL_ACTIVE) {
                    throw new IllegalStateException("Attempt no longer accepts output");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO ai_attempt_output(attempt_id, sequence_no, output_kind, content, visible, "
                                + "context_eligible, created_at) VALUES(?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                    statement.setString(1, requireText(attemptId, "attemptId"));
                    statement.setLong(2, sequence);
                    statement.setString(3, Objects.requireNonNull(kind, "kind").name());
                    statement.setString(4, content);
                    statement.setBoolean(5, visible);
                    statement.setBoolean(6, contextEligible);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (IllegalStateException exception) {
            if (hasSqlState(exception, "23505")) {
                throw new IllegalStateException("Attempt output sequence already exists", exception);
            }
            throw exception;
        }
    }

    @Override
    public List<AiAttemptOutput> listAttemptOutputs(String attemptId) {
        return inConnection(connection -> {
            List<AiAttemptOutput> outputs = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT sequence_no, output_kind, content, visible, context_eligible, created_at "
                            + "FROM ai_attempt_output WHERE attempt_id = ? ORDER BY sequence_no")) {
                statement.setString(1, requireText(attemptId, "attemptId"));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        outputs.add(new AiAttemptOutput(
                                attemptId,
                                resultSet.getLong("sequence_no"),
                                AiAttemptOutputKind.valueOf(resultSet.getString("output_kind")),
                                resultSet.getString("content"),
                                resultSet.getBoolean("visible"),
                                resultSet.getBoolean("context_eligible"),
                                resultSet.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            return outputs;
        });
    }

    @Override
    public void saveMessageModelSnapshot(String messageId, AiModelRef modelRef) {
        inTransaction(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT provider, model_id, access_type, route_kind FROM ai_message_model_snapshot "
                            + "WHERE message_id = ? FOR UPDATE")) {
                select.setString(1, requireText(messageId, "messageId"));
                try (ResultSet resultSet = select.executeQuery()) {
                    if (resultSet.next()) {
                        AiModelRef existing = readModelRef(resultSet);
                        if (!existing.equals(modelRef)) {
                            throw new IllegalStateException("Message model snapshot is immutable");
                        }
                        return null;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ai_message_model_snapshot(message_id, provider, model_id, access_type, "
                            + "route_kind, created_at) VALUES(?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                insert.setString(1, messageId);
                bindModelRef(insert, 2, modelRef);
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<AiModelRef> getMessageModelSnapshot(String messageId) {
        return inConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT provider, model_id, access_type, route_kind FROM ai_message_model_snapshot "
                            + "WHERE message_id = ?")) {
                statement.setString(1, requireText(messageId, "messageId"));
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(readModelRef(resultSet)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public boolean acquireProviderLease(AiProviderEnum provider, String attemptId, long expectedFenceGeneration) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return acquireProviderLeaseOnce(provider, attemptId, expectedFenceGeneration);
            } catch (IllegalStateException exception) {
                if (attempt == 1 || !hasSqlState(exception, "40001")) {
                    throw exception;
                }
            }
        }
        return false;
    }

    private boolean acquireProviderLeaseOnce(AiProviderEnum provider, String attemptId,
                                             long expectedFenceGeneration) {
        return inTransaction(connection -> {
            ensureProvider(connection, provider);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET lease_attempt_id = ?, lease_fence_generation = ?, "
                            + "lease_acquired_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE provider = ? AND lease_attempt_id IS NULL AND fence_generation = ? "
                            + "AND EXISTS (SELECT 1 FROM ai_attempt WHERE attempt_id = ? AND provider = ?)")) {
                statement.setString(1, requireText(attemptId, "attemptId"));
                statement.setLong(2, expectedFenceGeneration);
                statement.setString(3, provider.name());
                statement.setLong(4, expectedFenceGeneration);
                statement.setString(5, attemptId);
                statement.setString(6, provider.name());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<AiProviderLease> currentLease(AiProviderEnum provider) {
        return inTransaction(connection -> {
            ensureProvider(connection, provider);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT lease_attempt_id, lease_fence_generation, lease_acquired_at "
                            + "FROM ai_provider_connection WHERE provider = ?")) {
                statement.setString(1, provider.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    String attemptId = resultSet.getString("lease_attempt_id");
                    if (attemptId == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new AiProviderLease(provider, attemptId,
                            resultSet.getLong("lease_fence_generation"),
                            resultSet.getTimestamp("lease_acquired_at").toInstant()));
                }
            }
        });
    }

    @Override
    public boolean isAttemptLeaseActive(String attemptId) {
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM ai_attempt a JOIN ai_provider_connection p ON p.provider = a.provider "
                            + "WHERE a.attempt_id = ? AND p.lease_attempt_id = a.attempt_id "
                            + "AND p.state = ? AND a.state IN (?, ?, ?) FOR UPDATE")) {
                statement.setString(1, requireText(attemptId, "attemptId"));
                statement.setString(2, AiProviderConnectionState.CONNECTED.name());
                statement.setString(3, AiAttemptState.ACTIVE.name());
                statement.setString(4, AiAttemptState.OUTPUT_VISIBLE.name());
                statement.setString(5, AiAttemptState.TOOL_ACTIVE.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    @Override
    public boolean releaseProviderLease(AiProviderEnum provider, String attemptId) {
        return inTransaction(connection -> {
            ensureProvider(connection, provider);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET lease_attempt_id = NULL, "
                            + "lease_fence_generation = NULL, lease_acquired_at = NULL, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE provider = ? AND lease_attempt_id = ?")) {
                statement.setString(1, provider.name());
                statement.setString(2, requireText(attemptId, "attemptId"));
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public AiToolStartResult beginToolExecution(String attemptId, long sequence, String toolName,
                                                String argumentsHash, String effectFingerprint) {
        return inTransaction(connection -> {
            if (!isToolExecutionAllowed(connection, attemptId)) {
                return new AiToolStartResult(AiToolStartDecision.BLOCKED_UNCERTAIN, null);
            }
            Optional<AiToolExecution> existing = findToolByFingerprint(connection, attemptId, effectFingerprint);
            if (existing.isPresent()) {
                AiToolExecution execution = existing.get();
                AiToolStartDecision decision = execution.state() == AiToolExecutionState.COMPLETED
                        ? AiToolStartDecision.RETURN_RECORDED_RESULT
                        : AiToolStartDecision.BLOCKED_UNCERTAIN;
                return new AiToolStartResult(decision, execution);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_tool_execution(attempt_id, sequence_no, tool_name, arguments_hash, "
                            + "effect_fingerprint, state, updated_at) "
                            + "VALUES(?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                statement.setString(1, requireText(attemptId, "attemptId"));
                statement.setLong(2, sequence);
                statement.setString(3, requireText(toolName, "toolName"));
                statement.setString(4, requireText(argumentsHash, "argumentsHash"));
                statement.setString(5, requireText(effectFingerprint, "effectFingerprint"));
                statement.setString(6, AiToolExecutionState.STARTED.name());
                statement.executeUpdate();
            }
            return new AiToolStartResult(AiToolStartDecision.STARTED,
                    readToolExecution(connection, attemptId, sequence).orElseThrow());
        });
    }

    private boolean isToolExecutionAllowed(Connection connection, String attemptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT a.state FROM ai_attempt a JOIN ai_provider_connection p ON p.provider = a.provider "
                        + "WHERE a.attempt_id = ? AND p.lease_attempt_id = a.attempt_id "
                        + "AND p.state = ? AND a.state IN (?, ?) FOR UPDATE")) {
            statement.setString(1, requireText(attemptId, "attemptId"));
            statement.setString(2, AiProviderConnectionState.CONNECTED.name());
            statement.setString(3, AiAttemptState.ACTIVE.name());
            statement.setString(4, AiAttemptState.OUTPUT_VISIBLE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Override
    public void completeToolExecution(String attemptId, long sequence, String safeResultReference) {
        inTransaction(connection -> {
            if (!isToolCompletionAllowed(connection, attemptId)) {
                throw new IllegalStateException("Tool result is fenced from the provider attempt");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_tool_execution SET state = ?, safe_result_reference = ?, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE attempt_id = ? AND sequence_no = ? "
                            + "AND state = ?")) {
                statement.setString(1, AiToolExecutionState.COMPLETED.name());
                statement.setString(2, requireText(safeResultReference, "safeResultReference"));
                statement.setString(3, requireText(attemptId, "attemptId"));
                statement.setLong(4, sequence);
                statement.setString(5, AiToolExecutionState.STARTED.name());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Tool execution is not in STARTED state");
                }
            }
            return null;
        });
    }

    private boolean isToolCompletionAllowed(Connection connection, String attemptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ai_attempt a JOIN ai_provider_connection p ON p.provider = a.provider "
                        + "WHERE a.attempt_id = ? AND p.lease_attempt_id = a.attempt_id "
                        + "AND p.state = ? AND a.state IN (?, ?, ?) FOR UPDATE")) {
            statement.setString(1, requireText(attemptId, "attemptId"));
            statement.setString(2, AiProviderConnectionState.CONNECTED.name());
            statement.setString(3, AiAttemptState.ACTIVE.name());
            statement.setString(4, AiAttemptState.OUTPUT_VISIBLE.name());
            statement.setString(5, AiAttemptState.TOOL_ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Override
    public Optional<AiToolExecution> findToolExecution(String attemptId, long sequence) {
        return inConnection(connection -> readToolExecution(connection, attemptId, sequence));
    }

    @Override
    public int recoverStartedToolsAsOutcomeUnknown() {
        return inTransaction(connection -> {
            int affectedAttempts = countUncertainToolAttempts(connection);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ai_tool_execution SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE state = ?")) {
                update.setString(1, AiToolExecutionState.OUTCOME_UNKNOWN.name());
                update.setString(2, AiToolExecutionState.STARTED.name());
                update.executeUpdate();
            }
            reconcileUncertainToolAttempts(connection);
            return affectedAttempts;
        });
    }

    @Override
    public boolean markToolOutcomeUnknownAndReleaseLease(String attemptId) {
        return inTransaction(connection -> {
            AiProviderEnum provider;
            AiAttemptState state;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT provider, state FROM ai_attempt WHERE attempt_id = ? FOR UPDATE")) {
                select.setString(1, requireText(attemptId, "attemptId"));
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        return false;
                    }
                    provider = AiProviderEnum.valueOf(resultSet.getString("provider"));
                    state = AiAttemptState.valueOf(resultSet.getString("state"));
                }
            }
            try (PreparedStatement updateTools = connection.prepareStatement(
                    "UPDATE ai_tool_execution SET state = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE attempt_id = ? AND state = ?")) {
                updateTools.setString(1, AiToolExecutionState.OUTCOME_UNKNOWN.name());
                updateTools.setString(2, attemptId);
                updateTools.setString(3, AiToolExecutionState.STARTED.name());
                updateTools.executeUpdate();
            }
            if (state != AiAttemptState.COMPLETED
                    && state != AiAttemptState.FAILED
                    && state != AiAttemptState.INTERRUPTED
                    && state != AiAttemptState.OUTCOME_UNKNOWN
                    && state != AiAttemptState.TOOL_OUTCOME_UNKNOWN) {
                try (PreparedStatement updateAttempt = connection.prepareStatement(
                        "UPDATE ai_attempt SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE attempt_id = ?")) {
                    updateAttempt.setString(1, AiAttemptState.TOOL_OUTCOME_UNKNOWN.name());
                    updateAttempt.setString(2, attemptId);
                    updateAttempt.executeUpdate();
                }
            }
            try (PreparedStatement clearLease = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET lease_attempt_id = NULL, lease_fence_generation = NULL, "
                            + "lease_acquired_at = NULL, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE provider = ? AND lease_attempt_id = ?")) {
                clearLease.setString(1, provider.name());
                clearLease.setString(2, attemptId);
                clearLease.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public int recoverOrphanedAttemptsAndLeases() {
        return inTransaction(connection -> {
            try (PreparedStatement updateTools = connection.prepareStatement(
                    "UPDATE ai_tool_execution SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE state = ?")) {
                updateTools.setString(1, AiToolExecutionState.OUTCOME_UNKNOWN.name());
                updateTools.setString(2, AiToolExecutionState.STARTED.name());
                updateTools.executeUpdate();
            }
            int affected = reconcileUncertainToolAttempts(connection);
            affected += recoverAttemptStates(connection, AiAttemptState.INTERRUPTED, AiAttemptState.CREATED);
            affected += recoverAttemptStates(connection, AiAttemptState.OUTCOME_UNKNOWN,
                    AiAttemptState.SUBMITTING, AiAttemptState.ACTIVE, AiAttemptState.OUTPUT_VISIBLE);
            affected += recoverAttemptStates(connection, AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                    AiAttemptState.TOOL_ACTIVE);
            try (PreparedStatement clearLeases = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET lease_attempt_id = NULL, lease_fence_generation = NULL, "
                            + "lease_acquired_at = NULL, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE lease_attempt_id IS NOT NULL")) {
                clearLeases.executeUpdate();
            }
            return affected;
        });
    }

    private int countUncertainToolAttempts(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT attempt_id) FROM ai_tool_execution WHERE state IN (?, ?)")) {
            statement.setString(1, AiToolExecutionState.STARTED.name());
            statement.setString(2, AiToolExecutionState.OUTCOME_UNKNOWN.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int reconcileUncertainToolAttempts(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_attempt SET state = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE state <> ? AND attempt_id IN ("
                        + "SELECT DISTINCT attempt_id FROM ai_tool_execution WHERE state IN (?, ?))")) {
            statement.setString(1, AiAttemptState.TOOL_OUTCOME_UNKNOWN.name());
            statement.setString(2, AiAttemptState.TOOL_OUTCOME_UNKNOWN.name());
            statement.setString(3, AiToolExecutionState.STARTED.name());
            statement.setString(4, AiToolExecutionState.OUTCOME_UNKNOWN.name());
            return statement.executeUpdate();
        }
    }

    private int recoverAttemptStates(
            Connection connection,
            AiAttemptState target,
            AiAttemptState... sources) throws SQLException {
        String placeholders = String.join(", ", java.util.Collections.nCopies(sources.length, "?"));
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_attempt SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE state IN ("
                        + placeholders + ")")) {
            statement.setString(1, target.name());
            for (int index = 0; index < sources.length; index++) {
                statement.setString(index + 2, sources[index].name());
            }
            return statement.executeUpdate();
        }
    }

    @Override
    public String beginSignOut(AiProviderEnum provider) {
        return inTransaction(connection -> {
            ensureProvider(connection, provider);
            AiProviderConnection current = readConnectionForUpdate(connection, provider);
            String leasedAttemptId = readLeasedAttemptId(connection, provider);
            long nextFence = current.fenceGeneration() + 1;
            fenceLeasedAttemptForSignOut(connection, leasedAttemptId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_provider_connection SET state = ?, fence_generation = ?, "
                            + "lease_attempt_id = NULL, lease_fence_generation = NULL, lease_acquired_at = NULL, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE provider = ?")) {
                statement.setString(1, AiProviderConnectionState.DISCONNECTING.name());
                statement.setLong(2, nextFence);
                statement.setString(3, provider.name());
                statement.executeUpdate();
            }
            String sagaId = UUID.randomUUID().toString();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_provider_saga(saga_id, provider, saga_type, state, fence_generation, "
                            + "updated_at) VALUES(?, ?, 'SIGN_OUT', ?, ?, CURRENT_TIMESTAMP)")) {
                statement.setString(1, sagaId);
                statement.setString(2, provider.name());
                statement.setString(3, AiProviderSagaState.DISCONNECT_REQUESTED.name());
                statement.setLong(4, nextFence);
                statement.executeUpdate();
            }
            return sagaId;
        });
    }

    private String readLeasedAttemptId(Connection connection, AiProviderEnum provider) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lease_attempt_id FROM ai_provider_connection WHERE provider = ?")) {
            statement.setString(1, provider.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private void fenceLeasedAttemptForSignOut(Connection connection, String attemptId) throws SQLException {
        if (attemptId == null) {
            return;
        }
        boolean hasStartedTool;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ai_tool_execution WHERE attempt_id = ? AND state = ?")) {
            statement.setString(1, attemptId);
            statement.setString(2, AiToolExecutionState.STARTED.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                hasStartedTool = resultSet.next();
            }
        }
        if (hasStartedTool) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_tool_execution SET state = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE attempt_id = ? AND state = ?")) {
                statement.setString(1, AiToolExecutionState.OUTCOME_UNKNOWN.name());
                statement.setString(2, attemptId);
                statement.setString(3, AiToolExecutionState.STARTED.name());
                statement.executeUpdate();
            }
        }
        AiAttemptState target = hasStartedTool
                ? AiAttemptState.TOOL_OUTCOME_UNKNOWN : AiAttemptState.INTERRUPTED;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_attempt SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE attempt_id = ? "
                        + "AND state IN (?, ?, ?, ?, ?)")) {
            statement.setString(1, target.name());
            statement.setString(2, attemptId);
            statement.setString(3, AiAttemptState.CREATED.name());
            statement.setString(4, AiAttemptState.SUBMITTING.name());
            statement.setString(5, AiAttemptState.ACTIVE.name());
            statement.setString(6, AiAttemptState.OUTPUT_VISIBLE.name());
            statement.setString(7, AiAttemptState.TOOL_ACTIVE.name());
            statement.executeUpdate();
        }
    }

    @Override
    public void transitionSignOutSaga(String sagaId, AiProviderSagaState expected,
                                      AiProviderSagaState target, String errorCode) {
        if (!expected.canTransitionTo(target)) {
            throw new IllegalStateException("Illegal sign-out saga transition: " + expected + " -> " + target);
        }
        inTransaction(connection -> {
            AiProviderEnum provider;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT provider, state FROM ai_provider_saga WHERE saga_id = ? FOR UPDATE")) {
                select.setString(1, requireText(sagaId, "sagaId"));
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Sign-out saga does not exist");
                    }
                    AiProviderSagaState current = AiProviderSagaState.valueOf(resultSet.getString("state"));
                    if (current != expected) {
                        throw new IllegalStateException("Sign-out saga changed concurrently");
                    }
                    provider = AiProviderEnum.valueOf(resultSet.getString("provider"));
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ai_provider_saga SET state = ?, last_error_code = ?, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE saga_id = ? AND state = ?")) {
                update.setString(1, target.name());
                update.setString(2, errorCode);
                update.setString(3, sagaId);
                update.setString(4, expected.name());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Sign-out saga changed concurrently");
                }
            }
            if (target == AiProviderSagaState.LOCAL_CLEANUP) {
                clearProviderSelectionState(connection, provider);
            }
            if (target == AiProviderSagaState.DISCONNECT_FAILED) {
                updateProviderConnectionState(connection, provider, AiProviderConnectionState.DISCONNECT_FAILED);
            } else if (expected == AiProviderSagaState.DISCONNECT_FAILED
                    && target == AiProviderSagaState.LOGOUT_REQUESTED) {
                updateProviderConnectionState(connection, provider, AiProviderConnectionState.DISCONNECTING);
            }
            if (target == AiProviderSagaState.DISCONNECTED) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE ai_provider_connection SET state = ?, masked_account = NULL, "
                                + "discovered_at = NULL, discovery_error_code = NULL, lease_attempt_id = NULL, "
                                + "lease_fence_generation = NULL, lease_acquired_at = NULL, "
                                + "updated_at = CURRENT_TIMESTAMP WHERE provider = ?")) {
                    update.setString(1, AiProviderConnectionState.DISCONNECTED.name());
                    update.setString(2, provider.name());
                    update.executeUpdate();
                }
            }
            return null;
        });
    }

    private static void updateProviderConnectionState(Connection connection, AiProviderEnum provider,
                                                      AiProviderConnectionState state) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE ai_provider_connection SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE provider = ?")) {
            update.setString(1, state.name());
            update.setString(2, provider.name());
            update.executeUpdate();
        }
    }

    @Override
    public List<AiProviderSaga> findRecoverableSagas() {
        return inConnection(connection -> {
            List<AiProviderSaga> sagas = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT saga_id, provider, state, fence_generation, updated_at FROM ai_provider_saga "
                            + "WHERE state <> ? ORDER BY updated_at, saga_id")) {
                statement.setString(1, AiProviderSagaState.DISCONNECTED.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        sagas.add(new AiProviderSaga(
                                resultSet.getString("saga_id"),
                                AiProviderEnum.valueOf(resultSet.getString("provider")),
                                AiProviderSagaState.valueOf(resultSet.getString("state")),
                                resultSet.getLong("fence_generation"),
                                resultSet.getTimestamp("updated_at").toInstant()));
                    }
                }
            }
            return sagas;
        });
    }

    @Override
    public void startSecretImportAttempt(String importId, Instant expiresAt) {
        inTransaction(connection -> {
            lockSecretImportLedger(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "MERGE INTO ai_secret_import(import_id, item_id, state, expires_at, updated_at) "
                            + "KEY(import_id, item_id) VALUES(?, ?, 'ATTEMPT_STARTED', ?, CURRENT_TIMESTAMP)")) {
                statement.setString(1, requireText(importId, "importId"));
                statement.setString(2, SECRET_IMPORT_ATTEMPT_ITEM);
                statement.setTimestamp(3, Timestamp.from(Objects.requireNonNull(expiresAt, "expiresAt")));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<AiSecretImportItemAck> findSucceededSecretImportItem(String itemId) {
        return inConnection(connection -> findSucceededSecretImportItem(connection, itemId));
    }

    @Override
    public AiSecretImportBeginDecision beginSecretImportItem(String importId, String itemId,
                                                             String nonceHash, Instant expiresAt,
                                                             boolean confirmDefault) {
        return inTransaction(connection -> {
            lockSecretImportLedger(connection);
            if (findSucceededSecretImportItem(connection, itemId).isPresent()) {
                return AiSecretImportBeginDecision.ALREADY_SUCCEEDED;
            }
            try (PreparedStatement uncertain = connection.prepareStatement(
                    "SELECT 1 FROM ai_secret_import WHERE item_id = ? AND state = 'WRITE_STARTED' LIMIT 1")) {
                uncertain.setString(1, requireText(itemId, "itemId"));
                try (ResultSet resultSet = uncertain.executeQuery()) {
                    if (resultSet.next()) {
                        return AiSecretImportBeginDecision.BLOCKED_OUTCOME_UNKNOWN;
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "MERGE INTO ai_secret_import(import_id, item_id, state, nonce_hash, expires_at, "
                            + "confirm_default, last_error_code, updated_at) KEY(import_id, item_id) "
                            + "VALUES(?, ?, 'ITEM_PENDING', ?, ?, ?, NULL, CURRENT_TIMESTAMP)")) {
                statement.setString(1, requireText(importId, "importId"));
                statement.setString(2, itemId);
                statement.setString(3, requireText(nonceHash, "nonceHash"));
                statement.setTimestamp(4, Timestamp.from(Objects.requireNonNull(expiresAt, "expiresAt")));
                statement.setBoolean(5, confirmDefault);
                statement.executeUpdate();
            }
            return AiSecretImportBeginDecision.STARTED;
        });
    }

    @Override
    public void markSecretImportWriteStarted(String importId, String itemId) {
        transitionSecretImportItem(importId, itemId, "ITEM_PENDING", "WRITE_STARTED", null);
    }

    @Override
    public void completeSecretImportItem(String importId, String itemId,
                                         AiSecretImportItemAck acknowledgement) {
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        inTransaction(connection -> {
            lockSecretImportLedger(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_secret_import SET state = 'SUCCEEDED', config_id = ?, config_name = ?, "
                            + "provider_name = ?, model_name = ?, has_credential = ?, default_config = ?, "
                            + "last_error_code = NULL, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE import_id = ? AND item_id = ? AND state = 'WRITE_STARTED'")) {
                statement.setString(1, acknowledgement.configId());
                statement.setString(2, acknowledgement.name());
                statement.setString(3, acknowledgement.provider());
                statement.setString(4, acknowledgement.model());
                statement.setBoolean(5, acknowledgement.hasCredential());
                statement.setBoolean(6, acknowledgement.defaultConfig());
                statement.setString(7, requireText(importId, "importId"));
                statement.setString(8, requireText(itemId, "itemId"));
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Secret import item is not write-started");
                }
            }
            return null;
        });
    }

    @Override
    public void failSecretImportItemBeforeWrite(String importId, String itemId, String errorCode) {
        transitionSecretImportItem(importId, itemId, "ITEM_PENDING", "FAILED", requireText(errorCode, "errorCode"));
    }

    @Override
    public void completeSecretImportAttempt(String importId) {
        transitionSecretImportItem(importId, SECRET_IMPORT_ATTEMPT_ITEM,
                "ATTEMPT_STARTED", "ATTEMPT_COMPLETED", null);
    }

    @Override
    public void cancelSecretImportAttempt(String importId) {
        inTransaction(connection -> {
            lockSecretImportLedger(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_secret_import SET state = 'CANCELLED', updated_at = CURRENT_TIMESTAMP "
                            + "WHERE import_id = ? AND state IN ('ATTEMPT_STARTED', 'ITEM_PENDING')")) {
                statement.setString(1, requireText(importId, "importId"));
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Optional<AiSecretImportItemAck> findSucceededSecretImportItem(Connection connection, String itemId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT config_id, config_name, provider_name, model_name, has_credential, default_config "
                        + "FROM ai_secret_import WHERE item_id = ? AND state = 'SUCCEEDED' "
                        + "ORDER BY updated_at DESC LIMIT 1")) {
            statement.setString(1, requireText(itemId, "itemId"));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AiSecretImportItemAck(itemId,
                        resultSet.getString("config_id"), resultSet.getString("config_name"),
                        resultSet.getString("provider_name"), resultSet.getString("model_name"),
                        resultSet.getBoolean("has_credential"), resultSet.getBoolean("default_config")));
            }
        }
    }

    private void transitionSecretImportItem(String importId, String itemId, String expected,
                                            String target, String errorCode) {
        inTransaction(connection -> {
            lockSecretImportLedger(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_secret_import SET state = ?, last_error_code = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE import_id = ? AND item_id = ? AND state = ?")) {
                statement.setString(1, target);
                statement.setString(2, errorCode);
                statement.setString(3, requireText(importId, "importId"));
                statement.setString(4, requireText(itemId, "itemId"));
                statement.setString(5, expected);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Secret import state changed concurrently");
                }
            }
            return null;
        });
    }

    private static void lockSecretImportLedger(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lock_id FROM ai_secret_import_lock WHERE lock_id = 1 FOR UPDATE");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Secret import ledger lock is unavailable");
            }
        }
    }

    private static void clearProviderSelectionState(Connection connection, AiProviderEnum provider)
            throws SQLException {
        try (PreparedStatement deleteModels = connection.prepareStatement(
                "DELETE FROM ai_model_snapshot WHERE provider = ?")) {
            deleteModels.setString(1, provider.name());
            deleteModels.executeUpdate();
        }
        try (PreparedStatement deletePreferences = connection.prepareStatement(
                "DELETE FROM ai_model_preference WHERE provider = ?")) {
            deletePreferences.setString(1, provider.name());
            deletePreferences.executeUpdate();
        }
    }

    private static void writeModelSnapshot(Connection connection, AiProviderEnum provider,
                                           List<AiModelSnapshot> models) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM ai_model_snapshot WHERE provider = ?")) {
            delete.setString(1, provider.name());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ai_model_snapshot(provider, model_id, access_type, route_kind, display_name, "
                        + "discovered_at, available, disabled_reason, supported_reasoning_efforts, "
                        + "default_reasoning_effort) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (AiModelSnapshot model : models) {
                insert.setString(1, provider.name());
                insert.setString(2, model.modelRef().modelId());
                insert.setString(3, model.modelRef().accessType().name());
                insert.setString(4, model.modelRef().routeKind().name());
                insert.setString(5, model.displayName());
                insert.setTimestamp(6, Timestamp.from(model.discoveredAt()));
                insert.setBoolean(7, model.available());
                insert.setString(8, model.disabledReason());
                insert.setString(9, encodeReasoningEfforts(model.supportedReasoningEfforts()));
                insert.setString(10, model.defaultReasoningEffort());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static String encodeReasoningEfforts(List<String> efforts) {
        return efforts == null || efforts.isEmpty() ? null : String.join(",", efforts);
    }

    private static List<String> decodeReasoningEfforts(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        return List.of(encoded.split(","));
    }

    private void ensureProvider(Connection connection, AiProviderEnum provider) throws SQLException {
        Objects.requireNonNull(provider, "provider");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_provider_connection(provider, state, fence_generation, updated_at) "
                        + "VALUES(?, ?, 0, CURRENT_TIMESTAMP)")) {
            statement.setString(1, provider.name());
            statement.setString(2, AiProviderConnectionState.DISCONNECTED.name());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!"23505".equals(exception.getSQLState())) {
                throw exception;
            }
        }
    }

    private AiProviderConnection readConnection(Connection connection, AiProviderEnum provider) throws SQLException {
        return readConnection(connection, provider, false);
    }

    private AiProviderConnection readConnectionForUpdate(Connection connection, AiProviderEnum provider) throws SQLException {
        return readConnection(connection, provider, true);
    }

    private AiProviderConnection readConnection(Connection connection, AiProviderEnum provider, boolean forUpdate)
            throws SQLException {
        String sql = "SELECT state, masked_account, fence_generation, discovered_at, discovery_error_code "
                + "FROM ai_provider_connection WHERE provider = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, provider.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Provider state is missing: " + provider);
                }
                Timestamp discoveredAt = resultSet.getTimestamp("discovered_at");
                return new AiProviderConnection(provider,
                        AiProviderConnectionState.valueOf(resultSet.getString("state")),
                        resultSet.getString("masked_account"),
                        resultSet.getLong("fence_generation"),
                        discoveredAt == null ? null : discoveredAt.toInstant(),
                        resultSet.getString("discovery_error_code"));
            }
        }
    }

    private Optional<AiToolExecution> findToolByFingerprint(Connection connection, String attemptId,
                                                            String effectFingerprint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence_no, tool_name, arguments_hash, effect_fingerprint, state, "
                        + "safe_result_reference, updated_at FROM ai_tool_execution "
                        + "WHERE attempt_id = ? AND effect_fingerprint = ?")) {
            statement.setString(1, requireText(attemptId, "attemptId"));
            statement.setString(2, requireText(effectFingerprint, "effectFingerprint"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readToolExecution(attemptId, resultSet))
                        : Optional.empty();
            }
        }
    }

    private Optional<AiToolExecution> readToolExecution(Connection connection, String attemptId, long sequence)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence_no, tool_name, arguments_hash, effect_fingerprint, state, "
                        + "safe_result_reference, updated_at FROM ai_tool_execution "
                        + "WHERE attempt_id = ? AND sequence_no = ?")) {
            statement.setString(1, requireText(attemptId, "attemptId"));
            statement.setLong(2, sequence);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readToolExecution(attemptId, resultSet))
                        : Optional.empty();
            }
        }
    }

    private static AiToolExecution readToolExecution(String attemptId, ResultSet resultSet) throws SQLException {
        return new AiToolExecution(
                attemptId,
                resultSet.getLong("sequence_no"),
                resultSet.getString("tool_name"),
                resultSet.getString("arguments_hash"),
                resultSet.getString("effect_fingerprint"),
                AiToolExecutionState.valueOf(resultSet.getString("state")),
                resultSet.getString("safe_result_reference"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static AiModelRef readModelRef(ResultSet resultSet) throws SQLException {
        return new AiModelRef(
                AiAccessType.valueOf(resultSet.getString("access_type")),
                AiProviderEnum.valueOf(resultSet.getString("provider")),
                AiRouteKind.valueOf(resultSet.getString("route_kind")),
                resultSet.getString("model_id"));
    }

    private static void validateSnapshot(AiProviderEnum provider, List<AiModelSnapshot> models,
                                         AiModelRef globalDefault) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(globalDefault, "globalDefault");
        if (globalDefault.provider() != provider) {
            throw new IllegalArgumentException("Global default provider does not match snapshot provider");
        }
        boolean foundAvailableDefault = false;
        for (AiModelSnapshot model : models) {
            if (model.modelRef().provider() != provider) {
                throw new IllegalArgumentException("Snapshot contains a model from another provider");
            }
            if (model.modelRef().equals(globalDefault) && model.available()) {
                foundAvailableDefault = true;
            }
        }
        if (!foundAvailableDefault) {
            throw new IllegalArgumentException("Global default must be present and available in the snapshot");
        }
    }

    private static void bindModelRef(PreparedStatement statement, int start, AiModelRef modelRef)
            throws SQLException {
        statement.setString(start, modelRef.provider().name());
        statement.setString(start + 1, modelRef.modelId());
        statement.setString(start + 2, modelRef.accessType().name());
        statement.setString(start + 3, modelRef.routeKind().name());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static boolean hasSqlState(Throwable throwable, String sqlState) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private <T> T inConnection(SqlWork<T> work) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            return work.apply(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("AI state ledger operation failed", exception);
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("AI state ledger transaction failed", exception);
        }
    }

    private static List<String> schemaStatements() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS subscription_ai_schema_version (version INT PRIMARY KEY, installed_at TIMESTAMP NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ai_provider_connection (provider VARCHAR(32) PRIMARY KEY, state VARCHAR(32) NOT NULL, masked_account VARCHAR(320), fence_generation BIGINT NOT NULL, discovered_at TIMESTAMP, discovery_error_code VARCHAR(96), lease_attempt_id VARCHAR(64), lease_fence_generation BIGINT, lease_acquired_at TIMESTAMP, updated_at TIMESTAMP NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ai_model_snapshot (provider VARCHAR(32) NOT NULL, model_id VARCHAR(160) NOT NULL, access_type VARCHAR(32) NOT NULL, route_kind VARCHAR(64) NOT NULL, display_name VARCHAR(240) NOT NULL, discovered_at TIMESTAMP NOT NULL, available BOOLEAN NOT NULL, disabled_reason VARCHAR(96), PRIMARY KEY(provider, model_id))",
                "ALTER TABLE ai_model_snapshot ADD COLUMN IF NOT EXISTS supported_reasoning_efforts VARCHAR(256)",
                "ALTER TABLE ai_model_snapshot ADD COLUMN IF NOT EXISTS default_reasoning_effort VARCHAR(32)",
                "CREATE TABLE IF NOT EXISTS ai_model_preference (scope_type VARCHAR(24) NOT NULL, scope_id VARCHAR(96) NOT NULL, provider VARCHAR(32) NOT NULL, model_id VARCHAR(160) NOT NULL, access_type VARCHAR(32) NOT NULL, route_kind VARCHAR(64) NOT NULL, updated_at TIMESTAMP NOT NULL, PRIMARY KEY(scope_type, scope_id))",
                "CREATE TABLE IF NOT EXISTS ai_message_model_snapshot (message_id VARCHAR(64) PRIMARY KEY, provider VARCHAR(32) NOT NULL, model_id VARCHAR(160) NOT NULL, access_type VARCHAR(32) NOT NULL, route_kind VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ai_attempt (attempt_id VARCHAR(64) PRIMARY KEY, message_id VARCHAR(64) NOT NULL, provider VARCHAR(32) NOT NULL, state VARCHAR(40) NOT NULL, external_thread_id VARCHAR(160), external_turn_id VARCHAR(160), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ai_attempt_output (attempt_id VARCHAR(64) NOT NULL, sequence_no BIGINT NOT NULL, output_kind VARCHAR(40) NOT NULL, content CLOB, visible BOOLEAN NOT NULL, context_eligible BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL, PRIMARY KEY(attempt_id, sequence_no))",
                "CREATE TABLE IF NOT EXISTS ai_tool_execution (attempt_id VARCHAR(64) NOT NULL, sequence_no BIGINT NOT NULL, tool_name VARCHAR(96) NOT NULL, arguments_hash VARCHAR(128) NOT NULL, effect_fingerprint VARCHAR(160) NOT NULL, state VARCHAR(40) NOT NULL, safe_result_reference VARCHAR(320), updated_at TIMESTAMP NOT NULL, PRIMARY KEY(attempt_id, sequence_no), UNIQUE(attempt_id, effect_fingerprint))",
                "CREATE TABLE IF NOT EXISTS ai_provider_saga (saga_id VARCHAR(64) PRIMARY KEY, provider VARCHAR(32) NOT NULL, saga_type VARCHAR(32) NOT NULL, state VARCHAR(40) NOT NULL, fence_generation BIGINT NOT NULL, last_error_code VARCHAR(96), updated_at TIMESTAMP NOT NULL)",
                "CREATE TABLE IF NOT EXISTS ai_secret_import (import_id VARCHAR(64) NOT NULL, item_id VARCHAR(64) NOT NULL, state VARCHAR(40) NOT NULL, nonce_hash VARCHAR(128), expires_at TIMESTAMP, updated_at TIMESTAMP NOT NULL, PRIMARY KEY(import_id, item_id))",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS confirm_default BOOLEAN DEFAULT FALSE NOT NULL",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS last_error_code VARCHAR(96)",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS config_id VARCHAR(160)",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS config_name VARCHAR(240)",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS provider_name VARCHAR(64)",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS model_name VARCHAR(160)",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS has_credential BOOLEAN DEFAULT FALSE NOT NULL",
                "ALTER TABLE ai_secret_import ADD COLUMN IF NOT EXISTS default_config BOOLEAN DEFAULT FALSE NOT NULL",
                "CREATE TABLE IF NOT EXISTS ai_secret_import_lock (lock_id INT PRIMARY KEY)",
                "MERGE INTO ai_secret_import_lock(lock_id) KEY(lock_id) VALUES(1)"
        );
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }
}
