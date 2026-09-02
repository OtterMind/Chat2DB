package ai.chat2db.community.storage.task;

import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the embedded H2 file database behind {@link H2TaskStorage}: the JDBC url, connection
 * creation and schema bootstrap.
 */
@Slf4j
final class TaskDatabase implements AutoCloseable {

    static final int SCHEMA_VERSION = 3;

    /**
     * Sibling of the {@code task-v2} directory written by {@code FileTaskStorage}, so every task
     * storage generation lives under the same storage root.
     */
    static final String DATABASE_DIRECTORY = "task-h2";

    private static final String SCHEMA_VERSION_KEY = "schema_version";

    private static final String[] SCHEMA_SQL = {
            "CREATE TABLE IF NOT EXISTS task ("
                    + "id BIGINT PRIMARY KEY,"
                    + "type VARCHAR(64),"
                    + "name CLOB,"
                    + "status VARCHAR(64) NOT NULL,"
                    + "progress INT NOT NULL,"
                    + "stage VARCHAR(64),"
                    + "progress_message CLOB,"
                    + "error_code VARCHAR(64),"
                    + "error_message CLOB,"
                    + "artifact_id CLOB,"
                    + "target_json CLOB,"
                    + "spec_json CLOB,"
                    + "user_id BIGINT,"
                    + "organization_id BIGINT,"
                    + "created_at BIGINT,"
                    + "started_at BIGINT,"
                    + "finished_at BIGINT,"
                    + "updated_at BIGINT,"
                    + "last_event_sequence BIGINT NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS idx_task_scope ON task(user_id, organization_id, status)",
            "CREATE TABLE IF NOT EXISTS task_event ("
                    + "task_id BIGINT NOT NULL,"
                    + "sequence BIGINT NOT NULL,"
                    + "event_id BIGINT,"
                    + "level VARCHAR(64),"
                    + "code VARCHAR(64),"
                    + "stage VARCHAR(64),"
                    + "message CLOB,"
                    + "details CLOB,"
                    + "created_at BIGINT,"
                    + "PRIMARY KEY (task_id, sequence))",
            "CREATE TABLE IF NOT EXISTS task_artifact ("
                    + "task_id BIGINT NOT NULL,"
                    + "artifact_id VARCHAR(1024) NOT NULL,"
                    + "role VARCHAR(32) NOT NULL,"
                    + "media_type VARCHAR(128),"
                    + "size_bytes BIGINT,"
                    + "created_at BIGINT,"
                    + "PRIMARY KEY (task_id, artifact_id))",
            "CREATE TABLE IF NOT EXISTS resume_state ("
                    + "task_id BIGINT NOT NULL,"
                    + "shard_no INT NOT NULL,"
                    + "kind VARCHAR(32) NOT NULL,"
                    + "cursor_json CLOB,"
                    + "rows_done BIGINT,"
                    + "bytes_done BIGINT,"
                    + "updated_at BIGINT,"
                    + "PRIMARY KEY (task_id, shard_no))",
            "CREATE TABLE IF NOT EXISTS schema_meta ("
                    + "meta_key VARCHAR(64) PRIMARY KEY,"
                    + "meta_value CLOB)",
    };

    /**
     * Statements that add columns to databases created by an older schema version; idempotent so
     * they are safe on a freshly created database as well.
     */
    private static final String[] UPGRADE_SQL = {
            "ALTER TABLE task ADD COLUMN IF NOT EXISTS spec_json CLOB",
    };

    private final String jdbcUrl;

    private boolean initialized;

    TaskDatabase(String storageBasePath) {
        File directory = new File(storageBasePath, DATABASE_DIRECTORY);
        FileUtil.mkdir(directory);
        String databaseFile = new File(directory, "task").getAbsolutePath().replace(File.separatorChar, '/');
        // DB_CLOSE_DELAY keeps the store open between operations, so a connection per operation does
        // not pay for re-opening the MVStore; LOCK_TIMEOUT covers serialized event-sequence allocation.
        this.jdbcUrl = "jdbc:h2:" + databaseFile + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
    }

    static String defaultStorageBasePath() {
        return ConfigUtils.getEnvBasePath() + File.separator + "storage";
    }

    /**
     * Opens a connection with manual commit, so every write path has to decide explicitly where its
     * transaction boundary is.
     */
    Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, null, null);
        connection.setAutoCommit(false);
        return connection;
    }

    synchronized void initialize() {
        if (initialized) {
            return;
        }
        loadDriver();
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : SCHEMA_SQL) {
                    statement.execute(sql);
                }
            }
            int stored = readSchemaVersion(connection);
            if (stored > SCHEMA_VERSION) {
                throw new IllegalStateException("Task storage schema version " + stored
                        + " is newer than this build supports (" + SCHEMA_VERSION + ")");
            }
            if (stored > 0 && stored < SCHEMA_VERSION) {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : UPGRADE_SQL) {
                        statement.execute(sql);
                    }
                }
            }
            if (stored != SCHEMA_VERSION) {
                writeSchemaVersion(connection);
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize task storage", e);
        }
        initialized = true;
    }

    private int readSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT meta_value FROM schema_meta WHERE meta_key = ?")) {
            statement.setString(1, SCHEMA_VERSION_KEY);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Integer.parseInt(rows.getString(1).trim()) : 0;
            }
        }
    }

    private void writeSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "MERGE INTO schema_meta (meta_key, meta_value) KEY(meta_key) VALUES (?, ?)")) {
            statement.setString(1, SCHEMA_VERSION_KEY);
            statement.setString(2, String.valueOf(SCHEMA_VERSION));
            statement.executeUpdate();
        }
    }

    private void loadDriver() {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("H2 driver is not available", e);
        }
    }

    @Override
    public synchronized void close() {
        if (!initialized) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, null, null);
                Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException e) {
            log.warn("Could not close task storage cleanly", e);
        }
        initialized = false;
    }
}
