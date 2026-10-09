package ai.chat2db.plugin.sqlserver;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import com.alibaba.druid.DbType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlServerExecutorTest {

    private static final long DATA_SOURCE_ID = 707L;

    private IPlugin previousPlugin;
    private boolean contextBound;

    @BeforeAll
    static void setUpI18n() throws Exception {
        Field field = I18nUtils.class.getDeclaredField("messageSourceStatic");
        field.setAccessible(true);
        field.set(null, new MessageSource() {
            @Override
            public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
                return defaultMessage == null ? code : defaultMessage;
            }

            @Override
            public String getMessage(String code, Object[] args, Locale locale) {
                return code;
            }

            @Override
            public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
                String[] codes = resolvable.getCodes();
                return codes == null || codes.length == 0 ? resolvable.getDefaultMessage() : codes[0];
            }
        });
    }

    @AfterEach
    void tearDownContext() {
        if (!contextBound) {
            return;
        }
        Chat2DBContext.removeContext();
        ConnectionPool.removeConnection(DATA_SOURCE_ID);
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove("SQLSERVER");
        } else {
            Chat2DBContext.PLUGIN_MAP.put("SQLSERVER", previousPlugin);
        }
    }

    @Test
    void shouldKeepGoBatchWhenExecutingFormalSql() {
        SqlServerExecutor executor = new SqlServerExecutor();
        SqlExecuteRequest command = new SqlExecuteRequest();
        command.setScript("SET SHOWPLAN_XML ON;\nGO\nSELECT * FROM uf_wtbhb WHERE lcid=1208045;\nGO\nSET SHOWPLAN_XML OFF;");
        command.setExplain(false);

        executor.prepareCommandScript(command);

        assertEquals("SET SHOWPLAN_XML ON;\nGO\nSELECT * FROM uf_wtbhb WHERE lcid=1208045;\nGO\nSET SHOWPLAN_XML OFF;",
                command.getScript());
    }

    @Test
    void shouldSplitSqlServerBatchByGoDelimiter() {
        SqlServerExecutor executor = new SqlServerExecutor();

        List<String> sqlList = executor.splitByGO(
                "SET SHOWPLAN_XML ON;\nGO\nSELECT * FROM uf_wtbhb WHERE lcid=1208045;\nGO\nSET SHOWPLAN_XML OFF;");

        assertEquals(List.of(
                "SET SHOWPLAN_XML ON;",
                "SELECT * FROM uf_wtbhb WHERE lcid=1208045;",
                "SET SHOWPLAN_XML OFF;"), sqlList);
    }

    @Test
    void shouldSplitInlineGoAfterStatementTerminator() {
        SqlServerExecutor executor = new SqlServerExecutor();

        List<String> sqlList = executor.splitByGO(
                "SET SHOWPLAN_XML ON;\nGO\nSELECT * FROM uf_wtbhb WHERE lcid=1208045;GO\nSET SHOWPLAN_XML OFF;");

        assertEquals(List.of(
                "SET SHOWPLAN_XML ON;",
                "SELECT * FROM uf_wtbhb WHERE lcid=1208045;",
                "SET SHOWPLAN_XML OFF;"), sqlList);
    }

    @Test
    void shouldExposeMetricsForGoBatch() throws Exception {
        SqlServerExecutor executor = new SqlServerExecutor();
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:sqlserver_go_metrics")) {
            ExecuteResponse result = executor.execute(SqlStatementExecuteRequest.builder()
                    .sql("CREATE TABLE sample (id INT PRIMARY KEY);\nGO\n"
                            + "INSERT INTO sample (id) VALUES (1);\nGO\n"
                            + "UPDATE sample SET id = 2 WHERE id = 1;")
                    .connection(connection)
                    .limitRowSize(true)
                    .offset(0)
                    .count(10)
                    .build());

            assertEquals(1, result.getUpdateCount());
            assertEquals(1, result.getStatementSequence());
            assertNotNull(result.getExecutionMetrics());
            assertNotNull(result.getExecutionMetrics().getStartedAtEpochMs());
            assertNotNull(result.getExecutionMetrics().getFinishedAtEpochMs());
            assertEquals(result.getExecutionMetrics().getTotalDurationMs(),
                    result.getExecutionMetrics().getExecuteDurationMs()
                            + result.getExecutionMetrics().getFetchDurationMs());
            assertTrue(result.getExecutionMetrics().getExecuteDurationMs() >= 0L);
            assertEquals(0L, result.getExecutionMetrics().getFetchDurationMs());
            assertEquals(0, result.getExecutionMetrics().getFetchedRowCount());
        }
    }

    @Test
    void shouldExposeIndependentMetricsForEveryGoBatchResult() throws Exception {
        TestSqlServerExecutor executor = new TestSqlServerExecutor();
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:sqlserver_go_multi_metrics")) {
            List<ExecuteResponse> results = executor.executeAll(
                    "CREATE TABLE sample (id INT PRIMARY KEY);\nGO\n"
                            + "INSERT INTO sample (id) VALUES (1);\nGO\n"
                            + "UPDATE sample SET id = 2 WHERE id = 1;",
                    connection);

            assertEquals(3, results.size());
            for (ExecuteResponse result : results) {
                assertNotNull(result.getExecutionMetrics());
                assertEquals(result.getExecutionMetrics().getTotalDurationMs(),
                        result.getExecutionMetrics().getExecuteDurationMs()
                                + result.getExecutionMetrics().getFetchDurationMs());
                assertEquals(0L, result.getExecutionMetrics().getFetchDurationMs());
            }
            assertTrue(results.get(1).getExecutionMetrics().getStartedAtEpochMs()
                    >= results.get(0).getExecutionMetrics().getStartedAtEpochMs());
            assertTrue(results.get(2).getExecutionMetrics().getStartedAtEpochMs()
                    >= results.get(1).getExecutionMetrics().getStartedAtEpochMs());
        }
    }

    @Test
    void topLevelStreamingTreatsGoBatchesAsIndependentStatements() throws Exception {
        List<String> preparedSql = new ArrayList<>();
        String[] catalog = {"source_database"};
        Connection connection = streamingConnection(catalog, preparedSql);
        putContext(connection);
        SqlExecuteRequest request = request(
                "USE target_database;\nGO\nUPDATE sample SET value = 1;\nGO\nUPDATE sample SET value = 2;");
        CapturingConsumer consumer = new CapturingConsumer();
        CountingStatementListener listener = new CountingStatementListener();

        new SqlServerExecutor().executeStreaming(request, consumer, listener, () -> false);

        assertEquals(3, consumer.startedSql.size());
        assertEquals(3, consumer.finishedResults.size());
        assertEquals(3, preparedSql.size());
        assertEquals(3, listener.created);
        assertEquals(List.of(1, 2, 3), consumer.finishedResults.stream()
                .map(ExecuteResponse::getStatementSequence).toList());
        assertEquals("target_database",
                consumer.finishedResults.get(1).getExecutionContext().getDatabaseName());
    }

    @Test
    void topLevelStreamingRemovesSingleTrailingGoDelimiter() throws Exception {
        List<String> preparedSql = new ArrayList<>();
        Connection connection = streamingConnection(new String[]{"source_database"}, preparedSql);
        putContext(connection);
        CapturingConsumer consumer = new CapturingConsumer();

        SqlExecuteRequest request = request("UPDATE sample SET value = 1;\nGO");
        request.setSingle(true);
        new SqlServerExecutor().executeStreaming(request, consumer, new CountingStatementListener(), () -> false);

        assertEquals(1, consumer.startedSql.size());
        assertEquals(List.of("UPDATE sample SET value = 1;"), preparedSql);
    }

    @Test
    void preservedBatchUsesPostExecutionContextWithoutSplittingOnSemicolons() throws Exception {
        assertPreservedBatchUsesPostExecutionContext(false);
    }

    @Test
    void singleSelectionPreservesCompositeBatchAndUsesPostExecutionContext() throws Exception {
        assertPreservedBatchUsesPostExecutionContext(true);
    }

    @Test
    void parameterisedGoBatchesAreRejectedBeforeReachingTheDatabase() {
        List<String> preparedSql = new ArrayList<>();
        putContext(streamingConnection(new String[]{"source_database"}, preparedSql));
        SqlExecuteRequest request = request("SELECT name FROM users WHERE id = :id\nGO\nSELECT 1");
        request.setParameters(Map.of("id", SqlParameterValue.string("123")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new SqlServerExecutor().execute(request));

        assertEquals("sqlParameter.multipleStatements", exception.getCode());
        assertEquals(List.of(), preparedSql);
    }

    @Test
    void singleTrailingGoStillBindsParameters() {
        List<String> preparedSql = new ArrayList<>();
        List<String> bindings = new ArrayList<>();
        putContext(bindingConnection(preparedSql, bindings));
        SqlExecuteRequest request = request("UPDATE sample SET value = 1 WHERE id = :id;\nGO");
        request.setSingle(true);
        request.setParameters(Map.of("id", SqlParameterValue.string("123")));

        List<ExecuteResponse> results = new SqlServerExecutor().execute(request);

        assertEquals(1, results.size());
        assertTrue(results.get(0).getSuccess());
        assertEquals(List.of("UPDATE sample SET value = 1 WHERE id = ?;"), preparedSql);
        assertEquals(List.of("setString(1, 123)"), bindings);
    }

    @Test
    void executeMultiRejectsBoundStatementThatStillContainsGoBatches() {
        TestSqlServerExecutor executor = new TestSqlServerExecutor();
        List<String> preparedSql = new ArrayList<>();
        SimpleSqlStatement bound = executor.bind("SELECT name FROM users WHERE id = :id\nGO\nSELECT 1",
                Map.of("id", SqlParameterValue.string("123")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> executor.executeStatement(bound, bindingConnection(preparedSql, new ArrayList<>())));

        assertEquals("sqlParameter.multipleStatements", exception.getCode());
        assertEquals(List.of(), preparedSql, "GO batches must not run with unbound placeholders");
    }

    @Test
    void executeMultiStillSplitsPlainGoBatches() throws Exception {
        List<String> preparedSql = new ArrayList<>();
        TestSqlServerExecutor executor = new TestSqlServerExecutor();

        executor.executeAll("UPDATE sample SET value = 1\nGO\nUPDATE sample SET value = 2",
                bindingConnection(preparedSql, new ArrayList<>()));

        assertEquals(List.of("UPDATE sample SET value = 1", "UPDATE sample SET value = 2"), preparedSql);
        assertFalse(preparedSql.stream().anyMatch(sql -> sql.contains("?")));
    }

    private void assertPreservedBatchUsesPostExecutionContext(boolean single) throws Exception {
        List<String> preparedSql = new ArrayList<>();
        String[] catalog = {"source_database"};
        Connection connection = streamingConnection(catalog, preparedSql);
        putContext(connection);
        String sql = "USE target_database; DECLARE @value INT = 1; UPDATE sample SET value = @value;";
        CapturingConsumer consumer = new CapturingConsumer();
        SqlExecuteRequest request = request(sql);
        request.setSingle(single);

        new SqlServerExecutor().executeStreaming(request, consumer,
                new CountingStatementListener(), () -> false);

        assertEquals(1, consumer.startedSql.size());
        assertEquals(1, preparedSql.size());
        assertEquals(sql, preparedSql.get(0));
        assertEquals(1, consumer.finishedResults.size());
        assertEquals("target_database",
                consumer.finishedResults.get(0).getExecutionContext().getDatabaseName());
    }

    private void putContext(Connection connection) {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put("SQLSERVER", new TestSqlServerPlugin());
        contextBound = true;
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(DATA_SOURCE_ID);
        connectInfo.setConsoleId(1L);
        connectInfo.setDbType("SQLSERVER");
        connectInfo.setDatabaseName("");
        connectInfo.setSchemaName("dbo");
        connectInfo.setUrl("jdbc:test:sqlserver");
        connectInfo.setConnection(connection);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("SQLSERVER");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    private static SqlExecuteRequest request(String sql) {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setScript(sql);
        request.setConsoleId(1L);
        request.setDataSourceId(DATA_SOURCE_ID);
        request.setDatabaseName("");
        request.setSchemaName("dbo");
        request.setPageNo(1);
        request.setPageSize(10);
        request.setErrorContinue(Boolean.TRUE);
        return request;
    }

    private static Connection streamingConnection(String[] catalog, List<String> preparedSql) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement((String) args[0], catalog, preparedSql);
                    case "getCatalog" -> catalog[0];
                    case "getSchema" -> "dbo";
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Connection bindingConnection(List<String> preparedSql, List<String> bindings) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> bindingStatement((String) args[0], preparedSql, bindings);
                    case "getCatalog" -> "source_database";
                    case "getSchema" -> "dbo";
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement bindingStatement(String sql, List<String> preparedSql, List<String> bindings) {
        preparedSql.add(sql);
        boolean[] exhausted = {false};
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 2 && args[0] instanceof Integer) {
                        bindings.add(name + "(" + args[0] + ", " + args[1] + ")");
                        return null;
                    }
                    return switch (name) {
                        case "execute" -> {
                            exhausted[0] = false;
                            yield false;
                        }
                        case "getUpdateCount" -> exhausted[0] ? -1 : 1;
                        case "getMoreResults" -> {
                            exhausted[0] = true;
                            yield false;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static PreparedStatement preparedStatement(String sql, String[] catalog, List<String> preparedSql) {
        preparedSql.add(sql);
        boolean[] exhausted = {false};
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        exhausted[0] = false;
                        if (sql.toLowerCase(Locale.ROOT).contains("use target_database")) {
                            catalog[0] = "target_database";
                        }
                        yield false;
                    }
                    case "getUpdateCount" -> exhausted[0] ? -1 : 1;
                    case "getMoreResults" -> {
                        exhausted[0] = true;
                        yield false;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class CapturingConsumer implements ISqlExecutionResultConsumer {

        private final List<String> startedSql = new ArrayList<>();
        private final List<ExecuteResponse> finishedResults = new ArrayList<>();

        @Override
        public void statementStarted(String sql, String originalSql, String comment) {
            startedSql.add(sql);
        }

        @Override
        public void resultStarted(ExecuteResponse result) {
        }

        @Override
        public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
        }

        @Override
        public void resultFinished(ExecuteResponse result) {
            finishedResults.add(result);
        }

        @Override
        public void updateCount(ExecuteResponse result) {
        }

        @Override
        public void statementFinished(String sql, long duration) {
        }
    }

    private static final class CountingStatementListener implements ISqlExecutionStatementListener {

        private int created;

        @Override
        public void onStatementCreated(Statement statement) {
            created++;
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }

    private static final class TestSqlServerPlugin extends SqlServerSyntaxPlugin implements IPlugin {

        private final DBConfig dbConfig = new DBConfig();

        private TestSqlServerPlugin() {
            dbConfig.setDbType("SQLSERVER");
            dbConfig.setSupportDatabase(true);
            dbConfig.setSupportSchema(true);
            dbConfig.setPreserveScriptBatchExecution(true);
        }

        @Override
        public DBConfig getDBConfig() {
            return dbConfig;
        }
    }

    private static final class TestSqlServerExecutor extends SqlServerExecutor {

        private List<ExecuteResponse> executeAll(String sql, Connection connection) throws Exception {
            return executeMulti(new SimpleSqlStatement(sql), connection, true, 0, 10, null);
        }

        private SimpleSqlStatement bind(String sql, Map<String, SqlParameterValue> parameters) {
            SqlExecuteRequest command = new SqlExecuteRequest();
            command.setScript(sql);
            command.setParameters(parameters);
            return bindSqlParameters(command, List.of(new SimpleSqlStatement(sql)), DbType.sqlserver, "SQLSERVER")
                    .get(0);
        }

        private List<ExecuteResponse> executeStatement(SimpleSqlStatement statement, Connection connection)
                throws Exception {
            return executeMulti(statement, connection, true, 0, 10, null);
        }
    }
}
