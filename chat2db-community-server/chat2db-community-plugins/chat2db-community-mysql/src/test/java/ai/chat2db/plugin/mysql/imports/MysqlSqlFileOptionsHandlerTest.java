package ai.chat2db.plugin.mysql.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.plugin.mysql.MysqlPlugin;
import org.antlr.v4.runtime.CommonToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlSqlFileOptionsHandlerTest {

    @Test
    void mysqlPluginExposesSqlFileImportPolicy() {
        assertTrue(new MysqlPlugin().getSqlFileImportManager() instanceof MysqlSqlFileImportManager);
    }

    @Test
    void commitsConfiguredBatchesAndRestoresAutoCommit() {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        Connection connection = connection(commits, rollbacks);
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("BATCH", "STOP", 2), context(), connection);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        handler.handle(new Statement("INSERT INTO test VALUES (2)"));
        handler.handle(new Statement("INSERT INTO test VALUES (3)"));
        handler.flush();

        assertEquals(2, commits.get());
        assertEquals(0, rollbacks.get());
    }

    @Test
    void rejectsTransactionControlInTransactionModes() {
        Connection connection = connection(new AtomicInteger(), new AtomicInteger());
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("SINGLE_TRANSACTION", "STOP", 1),
                context(), connection);

        assertThrows(TaskExecutionException.class, () -> handler.handle(new Statement("COMMIT")));
    }

    @Test
    void cancellationBetweenStatementsRollsBackAndRestoresAutoCommit() {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        List<Boolean> autoCommitChanges = new ArrayList<>();
        Connection connection = connection(commits, rollbacks, new AtomicInteger(), autoCommitChanges, false);
        RecordingContext context = new RecordingContext();
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("SINGLE_TRANSACTION", "STOP", 10),
                context, connection);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        context.cancelled = true;

        assertThrows(TaskCancelledException.class,
                () -> handler.handle(new Statement("INSERT INTO test VALUES (2)")));
        assertEquals(1, rollbacks.get());
        assertEquals(List.of(false, true), autoCommitChanges);
    }

    @Test
    void summaryCountsStatementsOnlyAfterCommitSucceeds() {
        RecordingContext context = new RecordingContext();
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("BATCH", "STOP", 2), context,
                connection(new AtomicInteger(), new AtomicInteger()));

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        handler.handle(new Statement("INSERT INTO test VALUES (2)"));
        handler.handle(new Statement("INSERT INTO test VALUES (3)"));
        handler.flush();

        assertEquals(3, context.lastInfoDetails.get("committedStatements"));
        assertFalse(context.lastInfoDetails.containsValue(null));
        assertEquals("", context.lastInfoDetails.get("unexecutedStatementRange"));
        assertFalse(context.lastInfoDetails.containsKey("cancelledStatement"));
    }

    @Test
    void leadingCommentsCannotBypassImplicitCommitValidation() {
        AtomicInteger executeCalls = new AtomicInteger();
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("BATCH", "STOP", 10), context(),
                connection(new AtomicInteger(), new AtomicInteger(), executeCalls, new ArrayList<>(), false));

        assertThrows(TaskExecutionException.class,
                () -> handler.handle(new Statement("/* migration */ DROP TABLE test")));
        assertEquals(0, executeCalls.get());
    }

    @Test
    void failedStatementLogOmitsSqlSecretsAndIncludesLineRange() {
        RecordingContext context = new RecordingContext();
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("SCRIPT", "CONTINUE", 10), context,
                connection(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new ArrayList<>(), true));
        Statement statement = new Statement("INSERT INTO users(password) VALUES ('secret-value')");
        CommonToken first = new CommonToken(0);
        first.setLine(7);
        CommonToken last = new CommonToken(0);
        last.setLine(9);
        statement.setFirstToken(first);
        statement.setLastToken(last);

        handler.handle(statement);

        assertFalse(context.lastErrorDetails.toString().contains("secret-value"));
        assertEquals(7, context.lastErrorDetails.get("startLine"));
        assertEquals(9, context.lastErrorDetails.get("endLine"));
    }

    @Test
    void batchStopSummaryReportsRollbackAndUnexecutedRange() {
        RecordingContext context = new RecordingContext();
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("BATCH", "STOP", 5), context,
                connection(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new ArrayList<>(),
                        Set.of(3)), 5);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        handler.handle(new Statement("INSERT INTO test VALUES (2)"));

        assertThrows(TaskExecutionException.class,
                () -> handler.handle(new Statement("INSERT INTO test VALUES (3)")));
        assertEquals(5, context.lastInfoDetails.get("totalStatements"));
        assertEquals(2, context.lastInfoDetails.get("successfulStatements"));
        assertEquals(0, context.lastInfoDetails.get("committedStatements"));
        assertEquals(2, context.lastInfoDetails.get("rolledBackStatements"));
        assertEquals(List.of("1-2"), context.lastInfoDetails.get("rolledBackStatementRanges"));
        assertEquals(List.of(3), context.lastInfoDetails.get("failedStatementNumbers"));
        assertEquals(2, context.lastInfoDetails.get("unexecutedStatements"));
        assertEquals("4-5", context.lastInfoDetails.get("unexecutedStatementRange"));
    }

    @Test
    void batchContinueCommitsOnlySuccessfulStatements() {
        RecordingContext context = new RecordingContext();
        AtomicInteger commits = new AtomicInteger();
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("BATCH", "CONTINUE", 2), context,
                connection(commits, new AtomicInteger(), new AtomicInteger(), new ArrayList<>(), Set.of(2)), 3);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        handler.handle(new Statement("INSERT INTO test VALUES (2)"));
        handler.handle(new Statement("INSERT INTO test VALUES (3)"));
        handler.flush();

        assertEquals(1, commits.get());
        assertEquals(2, context.lastInfoDetails.get("successfulStatements"));
        assertEquals(2, context.lastInfoDetails.get("committedStatements"));
        assertEquals(1, context.lastInfoDetails.get("failedStatements"));
        assertEquals(List.of(2), context.lastInfoDetails.get("failedStatementNumbers"));
        assertEquals(0, context.lastInfoDetails.get("rolledBackStatements"));
    }

    @Test
    void singleTransactionContinueOptionStopsAndRollsBack() {
        RecordingContext context = new RecordingContext();
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("SINGLE_TRANSACTION", "CONTINUE", 10),
                context, connection(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new ArrayList<>(),
                Set.of(2)), 3);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));

        assertThrows(TaskExecutionException.class,
                () -> handler.handle(new Statement("INSERT INTO test VALUES (2)")));
        assertEquals("STOP", context.lastInfoDetails.get("errorPolicy"));
        assertEquals(1, context.lastInfoDetails.get("rolledBackStatements"));
        assertEquals(1, context.lastInfoDetails.get("unexecutedStatements"));
        assertEquals("3", context.lastInfoDetails.get("unexecutedStatementRange"));
    }

    @Test
    void preflightRejectsExecutableCommentTransactionControl() {
        ISqlBatchHandler preflight = MysqlSqlFileOptionsHandler.preflightHandler(spec("BATCH", "STOP", 10), context());

        assertThrows(TaskExecutionException.class,
                () -> preflight.handle(new Statement("/*!40101 SET autocommit=0 */")));
    }

    @Test
    void cancelledJdbcStatementIsTaskCancellationAndRestoresAutoCommit() {
        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        List<Boolean> autoCommitChanges = new ArrayList<>();
        RecordingContext context = new RecordingContext();
        Connection connection = connection(new AtomicInteger(), rollbacks, executeCalls, autoCommitChanges,
                Set.of(1), () -> {}, new SQLException("statement cancelled"));
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("BATCH", "STOP", 10), context,
                connection, 2);

        assertThrows(TaskCancelledException.class,
                () -> handler.handle(new Statement("INSERT INTO test VALUES (1)")));

        assertEquals(1, executeCalls.get());
        assertEquals(1, rollbacks.get());
        assertEquals(List.of(false, true), autoCommitChanges);
        assertEquals(0, context.lastErrorDetails.size());
    }

    @Test
    void cancelledSummaryCannotBlockAutoCommitRestore() {
        AtomicInteger rollbacks = new AtomicInteger();
        List<Boolean> autoCommitChanges = new ArrayList<>();
        RecordingContext context = new RecordingContext();
        context.cancelInfoLogs = true;
        MysqlSqlFileOptionsHandler handler = new MysqlSqlFileOptionsHandler(spec("BATCH", "STOP", 10), context,
                connection(new AtomicInteger(), rollbacks, new AtomicInteger(), autoCommitChanges, Set.of(1)), 2);

        assertThrows(TaskExecutionException.class,
                () -> handler.handle(new Statement("INSERT INTO test VALUES (1)")));

        assertEquals(1, rollbacks.get());
        assertEquals(List.of(false, true), autoCommitChanges);
    }

    @Test
    void mysqlPreflightChecksInformationSchemaAndAllowsTransactionalTarget() {
        RecordingContext context = new RecordingContext();
        AtomicInteger metadataQueries = new AtomicInteger();
        ISqlBatchHandler preflight = MysqlSqlFileOptionsHandler.mysqlPreflightHandler(
                targetSpec("BATCH", "STOP", 10, "app"), context,
                mysqlMetadataConnection(Map.of("app.orders", "InnoDB"), metadataQueries, false));

        preflight.handle(new Statement("INSERT INTO `app`.`orders` VALUES (1)"));
        preflight.handle(new Statement("UPDATE orders SET name = CONCAT('o', 'k') WHERE id = 1"));
        preflight.handle(new Statement("REPLACE LOW_PRIORITY INTO orders VALUES (2)"));

        assertEquals(1, metadataQueries.get());
    }

    @Test
    void mysqlPreflightRejectsNonTransactionalTargetBeforeExecution() {
        ISqlBatchHandler preflight = MysqlSqlFileOptionsHandler.mysqlPreflightHandler(
                targetSpec("BATCH", "STOP", 10, "app"), context(),
                mysqlMetadataConnection(Map.of("app.audit_log", "MyISAM"), new AtomicInteger(), false));

        TaskExecutionException error = assertThrows(TaskExecutionException.class,
                () -> preflight.handle(new Statement("INSERT INTO audit_log VALUES (1)")));

        assertTrue(error.getMessage().contains("non-transactional"));
    }

    @Test
    void mysqlPreflightRejectsUnknownUnresolvableTargetBeforeExecution() {
        ISqlBatchHandler preflight = MysqlSqlFileOptionsHandler.mysqlPreflightHandler(
                targetSpec("SINGLE_TRANSACTION", "STOP", 10, "app"), context(),
                mysqlMetadataConnection(Map.of(), new AtomicInteger(), false));

        TaskExecutionException error = assertThrows(TaskExecutionException.class,
                () -> preflight.handle(new Statement("DELETE FROM missing_table WHERE id = 1")));

        assertTrue(error.getMessage().contains("Could not resolve MySQL DML target"));
    }

    @Test
    void mysqlPreflightRejectsTargetsWhenMetadataPermissionFails() {
        ISqlBatchHandler preflight = MysqlSqlFileOptionsHandler.mysqlPreflightHandler(
                targetSpec("BATCH", "STOP", 10, "app"), context(),
                mysqlMetadataConnection(Map.of("app.orders", "InnoDB"), new AtomicInteger(), true));

        TaskExecutionException error = assertThrows(TaskExecutionException.class,
                () -> preflight.handle(new Statement("REPLACE INTO orders VALUES (1)")));

        assertTrue(error.getMessage().contains("Could not verify MySQL DML target engine"));
    }

    private ImportTaskSpec spec(String commitMode, String errorPolicy, int batchSize) {
        return ImportTaskSpec.builder().commitMode(commitMode).errorPolicy(errorPolicy).batchSize(batchSize).build();
    }

    private ImportTaskSpec targetSpec(String commitMode, String errorPolicy, int batchSize, String databaseName) {
        return ImportTaskSpec.builder()
                .commitMode(commitMode)
                .errorPolicy(errorPolicy)
                .batchSize(batchSize)
                .target(ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot.builder()
                        .databaseName(databaseName)
                        .build())
                .build();
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks) {
        return connection(commits, rollbacks, new AtomicInteger(), new ArrayList<>(), false);
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks, AtomicInteger executeCalls,
                                  List<Boolean> autoCommitChanges, boolean failExecute) {
        return connection(commits, rollbacks, executeCalls, autoCommitChanges, failExecute ? Set.of(1) : Set.of());
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks, AtomicInteger executeCalls,
                                  List<Boolean> autoCommitChanges, Set<Integer> failingExecuteCalls) {
        return connection(commits, rollbacks, executeCalls, autoCommitChanges, failingExecuteCalls, () -> {});
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks, AtomicInteger executeCalls,
                                  List<Boolean> autoCommitChanges, Set<Integer> failingExecuteCalls,
                                  Runnable beforeExecuteFailure) {
        return connection(commits, rollbacks, executeCalls, autoCommitChanges, failingExecuteCalls,
                beforeExecuteFailure, new SQLException("statement failed", "42000", 1064));
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks, AtomicInteger executeCalls,
                                  List<Boolean> autoCommitChanges, Set<Integer> failingExecuteCalls,
                                  Runnable beforeExecuteFailure, SQLException executeFailure) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit" -> {
                        autoCommitChanges.add((Boolean) args[0]);
                        yield null;
                    }
                    case "close" -> null;
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks.incrementAndGet();
                        yield null;
                    }
                    case "createStatement" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class[]{java.sql.Statement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                if (!statementMethod.getName().equals("execute")) {
                                    return null;
                                }
                                int executeCall = executeCalls.incrementAndGet();
                                if (failingExecuteCalls.contains(executeCall)) {
                                    beforeExecuteFailure.run();
                                    throw executeFailure;
                                }
                                return false;
                            });
                    case "isClosed" -> false;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> null;
                });
    }

    private Connection mysqlMetadataConnection(Map<String, String> engines, AtomicInteger metadataQueries,
                                               boolean failMetadata) {
        Map<String, String> normalizedEngines = new HashMap<>();
        engines.forEach((key, value) -> normalizedEngines.put(key.toLowerCase(), value));
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCatalog" -> "app";
                    case "prepareStatement" -> preparedStatement(normalizedEngines, metadataQueries, failMetadata);
                    case "isClosed" -> false;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> null;
                });
    }

    private PreparedStatement preparedStatement(Map<String, String> engines, AtomicInteger metadataQueries,
                                                boolean failMetadata) {
        List<String> parameters = new ArrayList<>();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        int index = (Integer) args[0] - 1;
                        while (parameters.size() <= index) {
                            parameters.add(null);
                        }
                        parameters.set(index, (String) args[1]);
                        yield null;
                    }
                    case "executeQuery" -> {
                        metadataQueries.incrementAndGet();
                        if (failMetadata) {
                            throw new SQLException("permission denied for information_schema.TABLES", "42000", 1142);
                        }
                        String key = (parameters.get(0) + "." + parameters.get(1)).toLowerCase();
                        yield resultSet(engines.get(key));
                    }
                    case "close" -> null;
                    case "isClosed" -> false;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> null;
                });
    }

    private ResultSet resultSet(String engine) {
        AtomicInteger row = new AtomicInteger();
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> engine != null && row.incrementAndGet() == 1;
                    case "getString" -> engine;
                    case "close" -> null;
                    case "isClosed" -> false;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> null;
                });
    }

    private TaskExecutionContext context() {
        return new RecordingContext();
    }

    private static final class RecordingContext implements TaskExecutionContext {
        private boolean cancelled;
        private boolean cancelInfoLogs;
        private Map<String, Object> lastInfoDetails = Map.of();
        private Map<String, Object> lastErrorDetails = Map.of();

        @Override public void reportProgress(int progress, String stage, String message) { }
        @Override public void logInfo(String code, String message) { }
        @Override public void logInfo(String code, String message, Map<String, Object> details) {
            if (cancelInfoLogs) {
                throw new TaskCancelledException();
            }
            lastInfoDetails = details;
        }
        @Override public void logWarn(String code, String message, Map<String, Object> details) { }
        @Override public void logError(String code, String message, Map<String, Object> details) {
            lastErrorDetails = details;
        }
        @Override public void checkCancelled() {
            if (cancelled) {
                throw new TaskCancelledException();
            }
        }
        @Override public void registerCancelable(TaskCancelable resource) { }
        @Override public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) { return null; }
        @Override public void write(String content) { }
        @Override public void onStatementCreated(java.sql.Statement statement) { }
        @Override public void onStatementClosed(java.sql.Statement statement) { }
    }
}
