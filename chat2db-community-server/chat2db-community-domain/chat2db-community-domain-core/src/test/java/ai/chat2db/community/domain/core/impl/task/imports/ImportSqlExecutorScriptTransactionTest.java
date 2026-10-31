package ai.chat2db.community.domain.core.impl.task.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Records actual JDBC calls against H2: script-owned transactions must issue no batch commit,
 * while the standalone-batch control must really commit. PostgreSQL server-side rollback and
 * savepoint outcomes are tested separately by SQLScriptTransactionPostgreSQLIT.
 */
class ImportSqlExecutorScriptTransactionTest {

    private static final String DB_TYPE = "SCRIPT_TX_TEST";

    private Connection realConnection;

    private RecordingConnection recording;

    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() throws Exception {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }
        });
        realConnection = DriverManager.getConnection("jdbc:h2:mem:script_tx");
        try (Statement statement = realConnection.createStatement()) {
            statement.execute("CREATE TABLE TARGET_ROWS (ID INT PRIMARY KEY)");
        }
        // Production shape: a pooled connection that lets the batch executor manage its own
        // transaction unless the script opened one.
        realConnection.setAutoCommit(true);
        recording = RecordingConnection.wrap(realConnection);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(recording.proxy());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        realConnection.close();
    }

    @Test
    void aScriptTransactionStopsTheExecutorFromCommittingItsInsertBatch() throws Exception {
        assertAutoCommitBeforeBatch();
        new SqlScriptExecutor(new StubContext()).executeBatch(List.of(
                "BEGIN",
                "INSERT INTO TARGET_ROWS (ID) VALUES (1)",
                "INSERT INTO TARGET_ROWS (ID) VALUES (2)",
                "ROLLBACK"));

        assertFalse(recording.calls().contains("commit"),
                "no commit may run while the script owns the transaction: " + recording.calls());
        assertFalse(recording.calls().contains("setAutoCommit"),
                "the executor must not re-configure the script's transaction: " + recording.calls());
    }

    @Test
    void aScriptCommitAlsoLeavesCommittingToTheScript() throws Exception {
        assertAutoCommitBeforeBatch();
        new SqlScriptExecutor(new StubContext()).executeBatch(List.of(
                "BEGIN",
                "INSERT INTO TARGET_ROWS (ID) VALUES (7)",
                "COMMIT"));

        assertFalse(recording.calls().contains("commit"),
                "the script decides when the transaction ends: " + recording.calls());
    }

    @Test
    void aBatchWithoutAScriptTransactionStillCommitsOnItsOwn() throws Exception {
        assertAutoCommitBeforeBatch();
        new SqlScriptExecutor(new StubContext()).executeBatch(List.of(
                "INSERT INTO TARGET_ROWS (ID) VALUES (3)"));

        assertTrue(recording.calls().contains("commit"),
                "the executor must still commit when no script transaction is open: " + recording.calls());
        assertEquals(List.of(3), ids(), "the control case must really write its row");
    }

    @Test
    void theExecutorsOwnBatchPathReallyCommitsSoTheControlIsNotVacuous() throws Exception {
        assertAutoCommitBeforeBatch();
        DefaultSQLExecutor.getInstance().executeBatchInsert(
                Chat2DBContext.getConnection(), List.of("INSERT INTO TARGET_ROWS (ID) VALUES (99)"),
                new StubContext(), () -> { });

        assertTrue(recording.calls().contains("commit"),
                "the executor's own batch path must commit, otherwise the script case proves nothing: "
                        + recording.calls());
    }

    /**
     * Pins the precondition the assertions depend on: the executor must start from a connection that
     * would let it manage its own transaction, otherwise neither the commit nor its absence means
     * anything. The pool can hand back a connection created by an earlier test, so this is set on the
     * same handle the production code will use.
     */
    private void assertAutoCommitBeforeBatch() throws Exception {
        Connection connection = Chat2DBContext.getConnection();
        connection.setAutoCommit(true);
        assertTrue(connection.getAutoCommit(),
                "the batch must start from an auto-commit connection for the assertion to mean anything");
        // Drop the setup calls, so the recorded list only holds what the production path did.
        recording.reset();
    }

    private List<Integer> ids() throws Exception {
        try (Statement statement = realConnection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT ID FROM TARGET_ROWS ORDER BY ID")) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
            return ids;
        }
    }

    /** Records every method invoked on the wrapped connection so the mechanism is observable. */
    private static final class RecordingConnection {

        private final List<String> calls = new ArrayList<>();

        private final Connection proxy;

        private RecordingConnection(Connection delegate) {
            this.proxy = (Connection) Proxy.newProxyInstance(
                    RecordingConnection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (instance, method, arguments) -> {
                        calls.add(method.getName());
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        static RecordingConnection wrap(Connection connection) {
            return new RecordingConnection(connection);
        }

        /** The proxy handed to the production code; also the recording handle. */
        Connection proxy() {
            return proxy;
        }

        List<String> calls() {
            return List.copyOf(calls);
        }

        /** Forgets the calls made while the test was setting up its own preconditions. */
        void reset() {
            calls.clear();
        }
    }

    /** Minimal context: the executor only logs, checks cancellation and reports progress. */
    private static final class StubContext implements TaskExecutionContext {

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            throw new UnsupportedOperationException("an SQL batch does not publish artifacts");
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }
}
