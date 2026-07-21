package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionContext;
import ai.chat2db.community.domain.api.model.result.ExecutionMetrics;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.RefreshTarget;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.JdbcExecutionContext;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.druid.DbType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSQLExecutorExecutionMetricsTest {

    private static final String TEST_DB_TYPE = "H2";
    private static final DefaultSQLExecutor EXECUTOR = new TestSQLExecutor();

    private IPlugin previousPlugin;
    private TestPlugin testPlugin;

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

    @BeforeEach
    void setUpPlugin() {
        testPlugin = new TestPlugin();
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, testPlugin);
    }

    @AfterEach
    void tearDownContext() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void synchronousSelectAndUpdateExposeMetricsWithoutChangingResults() throws Exception {
        try (Connection connection = openDatabase("sync_metrics")) {
            putContext(connection);

            ExecuteResponse select = EXECUTOR.execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT * FROM sample ORDER BY id")
                    .connection(connection)
                    .limitRowSize(true)
                    .offset(0)
                    .count(10)
                    .build());

            assertEquals(2, select.getDataList().size());
            assertEquals(1, select.getStatementSequence());
            assertMetrics(select, 2);

            ExecuteResponse update = EXECUTOR.execute(SqlStatementExecuteRequest.builder()
                    .sql("UPDATE sample SET payload_text = 'updated' WHERE id = 1")
                    .connection(connection)
                    .limitRowSize(true)
                    .build());

            assertEquals(1, update.getUpdateCount());
            assertEquals(1, update.getStatementSequence());
            assertMetrics(update, 0);
        }
    }

    @Test
    void streamingResultsUseStatementOrderAndExposeFinalMetrics() throws Exception {
        try (Connection connection = openDatabase("stream_metrics")) {
            putContext(connection);
            SqlExecuteRequest request = request(
                    "SELECT 1; "
                            + "UPDATE sample SET payload_text = 'streamed' WHERE id = 2");
            CapturingConsumer consumer = new CapturingConsumer();

            EXECUTOR.executeStreaming(request, consumer, new NoOpStatementListener(),
                    () -> false);

            assertEquals(2, consumer.finishedResults.size());
            ExecuteResponse select = consumer.finishedResults.get(0);
            ExecuteResponse update = consumer.finishedResults.get(1);
            assertEquals(1, select.getStatementSequence());
            assertEquals(2, update.getStatementSequence());
            assertEquals(1, select.getDataList().size());
            assertEquals(1, update.getUpdateCount());
            assertMetrics(select, 1);
            assertMetrics(update, 0);
        }
    }

    @Test
    void nonStreamingScriptExposesIndependentMetricsForEveryResult() throws Exception {
        try (Connection connection = openDatabase("multi_metrics")) {
            putContext(connection);

            List<ExecuteResponse> results = EXECUTOR.execute(request(
                    "SELECT 1; SELECT 2"));

            assertEquals(2, results.size());
            assertEquals(1, results.get(0).getStatementSequence());
            assertEquals(2, results.get(1).getStatementSequence());
            assertMetrics(results.get(0), 1);
            assertMetrics(results.get(1), 1);
        }
    }

    @Test
    void resultCarriesJdbcContextInEffectWhenStatementStarts() throws Exception {
        try (Connection connection = openDatabase("execution_context")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA target_schema");
            }

            ExecuteResponse switchResult = EXECUTOR.execute(SqlStatementExecuteRequest.builder()
                    .sql("SET SCHEMA target_schema")
                    .connection(connection)
                    .limitRowSize(true)
                    .build());
            ExecuteResponse queryResult = EXECUTOR.execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT 1")
                    .connection(connection)
                    .limitRowSize(true)
                    .build());

            assertEquals("PUBLIC", switchResult.getExecutionContext().getSchemaName());
            assertEquals("TARGET_SCHEMA", queryResult.getExecutionContext().getSchemaName());
        }
    }

    @Test
    void topLevelScriptShowsNewSchemaAfterSuccessfulSchemaSwitch() throws Exception {
        try (Connection connection = openDatabase("top_level_schema_context")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA target_schema");
            }

            List<ExecuteResponse> results = EXECUTOR.execute(request(
                    "SET SCHEMA target_schema; SELECT 1"));

            assertEquals(2, results.size());
            assertEquals("PUBLIC", results.get(0).getExecutionContext().getSchemaName());
            assertEquals("TARGET_SCHEMA", results.get(1).getExecutionContext().getSchemaName());
        }
    }

    @Test
    void movingAnObjectToAnotherSchemaDoesNotChangeExecutionContext() throws Exception {
        try (Connection connection = openDatabase("alter_object_schema_context")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA target_schema");
            }

            List<ExecuteResponse> results = EXECUTOR.execute(request(
                    "ALTER TABLE sample SET SCHEMA target_schema; SELECT 1"));

            assertEquals(2, results.size());
            assertEquals("PUBLIC", results.get(0).getExecutionContext().getSchemaName());
            assertEquals("PUBLIC", results.get(1).getExecutionContext().getSchemaName());
        }
    }

    @Test
    void successfulUseDatabaseAdvancesOutputContextWithoutMutatingConnection() throws Exception {
        String[] catalog = {"source_database"};
        String[] schema = {"source_schema"};
        Connection connection = contextConnection(catalog, schema);
        JdbcExecutionContext.Cursor cursor = JdbcExecutionContext.cursor(connection);
        SimpleSqlStatement statement = useDatabaseStatement("target_database");

        ((TestSQLExecutor) EXECUTOR).advanceContext(cursor, statement, connection,
                List.of(ExecuteResponse.builder().success(Boolean.TRUE).build()));

        assertEquals("target_database", cursor.current().getDatabaseName());
        assertEquals("source_database", connection.getCatalog());

        ((TestSQLExecutor) EXECUTOR).advanceContext(cursor, new SimpleSqlStatement("SELECT 1"), connection,
                List.of(ExecuteResponse.builder().success(Boolean.TRUE).build()));
        assertEquals("target_database", cursor.current().getDatabaseName());

        JdbcExecutionContext.Cursor failedCursor = JdbcExecutionContext.cursor(connection);
        ((TestSQLExecutor) EXECUTOR).advanceContext(failedCursor, statement, connection,
                List.of(ExecuteResponse.builder().success(Boolean.FALSE).build()));
        assertEquals("source_database", failedCursor.current().getDatabaseName());
    }

    @Test
    void schemaContextUsesReadOnlyJdbcStateForLocalDefaultAndMultipleSearchPathTargets() {
        String[] catalog = {"source_database"};
        String[] schema = {"source_schema"};
        Connection connection = contextConnection(catalog, schema);
        JdbcExecutionContext.Cursor cursor = JdbcExecutionContext.cursor(connection);
        SimpleSqlStatement statement = setSchemaStatement("ignored_parser_target");
        ExecuteResponse success = ExecuteResponse.builder().success(Boolean.TRUE).build();

        schema[0] = "local_schema";
        ((TestSQLExecutor) EXECUTOR).advanceContext(cursor, statement, connection, List.of(success));
        assertEquals("local_schema", cursor.current().getSchemaName());

        schema[0] = "public";
        ((TestSQLExecutor) EXECUTOR).advanceContext(cursor, statement, connection, List.of(success));
        assertEquals("public", cursor.current().getSchemaName());

        schema[0] = "first_existing_schema";
        ((TestSQLExecutor) EXECUTOR).advanceContext(cursor, statement, connection, List.of(success));
        assertEquals("first_existing_schema", cursor.current().getSchemaName());

        schema[0] = "failed_schema";
        ((TestSQLExecutor) EXECUTOR).advanceContext(cursor, statement, connection,
                List.of(ExecuteResponse.builder().success(Boolean.FALSE).build()));
        assertEquals("failed_schema", cursor.current().getSchemaName());

        schema[0] = "empty_result_schema";
        ((TestSQLExecutor) EXECUTOR).advanceContext(cursor, statement, connection, List.of());
        assertEquals("empty_result_schema", cursor.current().getSchemaName());
    }

    @Test
    void topLevelExecutionsAlignTheSameConnectionToTheEditorDatabase() throws Exception {
        try (Connection connection = openDatabase("editor_alignment")) {
            putContext(connection);
            SqlExecuteRequest request = request("SELECT 1");
            request.setDatabaseName("editor_database");

            List<ExecuteResponse> results = EXECUTOR.execute(request);

            assertEquals("editor_database", testPlugin.dbManager.databaseName);
            assertEquals(connection, testPlugin.dbManager.connection);
            assertEquals("editor_database", results.get(0).getExecutionContext().getDatabaseName());

            testPlugin.dbManager.clear();
            CapturingConsumer consumer = new CapturingConsumer();
            EXECUTOR.executeStreaming(request, consumer, new NoOpStatementListener(), () -> false);

            assertEquals("editor_database", testPlugin.dbManager.databaseName);
            assertEquals(connection, testPlugin.dbManager.connection);
            assertEquals("editor_database",
                    consumer.finishedResults.get(0).getExecutionContext().getDatabaseName());
        }
    }

    @Test
    void canceledStreamingExecutionDoesNotAlignTheConnection() throws Exception {
        try (Connection connection = openDatabase("canceled_before_alignment")) {
            putContext(connection);
            SqlExecuteRequest request = request("SELECT 1");
            request.setDatabaseName("editor_database");

            assertThrows(SQLException.class, () -> EXECUTOR.executeStreaming(
                    request, new CapturingConsumer(), new NoOpStatementListener(), () -> true));

            assertNull(testPlugin.dbManager.connection);
            assertNull(testPlugin.dbManager.databaseName);
        }
    }

    @Test
    void jdbcMultiResultMeasuresEachResultTransitionIndependently() throws Exception {
        try (Connection connection = multiUpdateConnection(1, 2)) {
            List<ExecuteResponse> results = ((TestSQLExecutor) EXECUTOR).executeMultiResults(connection);

            assertEquals(2, results.size());
            assertEquals(1, results.get(0).getUpdateCount());
            assertEquals(2, results.get(1).getUpdateCount());
            assertMetrics(results.get(0), 0);
            assertMetrics(results.get(1), 0);
            assertTrue(results.get(1).getExecutionMetrics().getStartedAtEpochMs()
                    >= results.get(0).getExecutionMetrics().getStartedAtEpochMs());
        }
    }

    @Test
    void failedStatementRetainsAvailableTimingInformation() throws Exception {
        try (Connection connection = openDatabase("failed_metrics")) {
            putContext(connection);
            SqlExecuteRequest request = request("UPDATE missing_table SET payload_text = 'missing'");
            request.setSingle(true);

            ExecuteResponse result = EXECUTOR.execute(request).get(0);

            assertEquals(Boolean.FALSE, result.getSuccess());
            assertEquals(1, result.getStatementSequence());
            assertNotNull(result.getDuration());
            assertEquals("PUBLIC", result.getExecutionContext().getSchemaName());
            assertMetrics(result, 0);
        }
    }

    @Test
    void failedTimingIncludesPagingAttemptAndFallback() throws Exception {
        try (Connection connection = openDatabase("failed_paging_metrics")) {
            putContext(connection);
            FailingPagingExecutor executor = new FailingPagingExecutor();
            SqlExecuteRequest request = request("SELECT * FROM missing_table");

            ExecuteResponse result = executor.execute(request).get(0);

            assertEquals(2, executor.attempts);
            assertEquals(Boolean.FALSE, result.getSuccess());
            assertTrue(result.getDuration() >= 60L);
            assertEquals("PUBLIC", result.getExecutionContext().getSchemaName());
            assertMetrics(result, 0);
        }
    }

    @Test
    void canceledContextChangingStatementKeepsStatementStartContext() throws Exception {
        try (Connection connection = openDatabase("canceled_context")) {
            putContext(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA target_schema");
            }
            CapturingConsumer consumer = new CapturingConsumer();
            AtomicInteger cancellationChecks = new AtomicInteger();

            EXECUTOR.executeStreaming(request("SET SCHEMA target_schema"), consumer,
                    new NoOpStatementListener(), () -> cancellationChecks.incrementAndGet() >= 4);

            assertEquals(1, consumer.finishedResults.size());
            ExecuteResponse result = consumer.finishedResults.get(0);
            assertEquals(Boolean.FALSE, result.getSuccess());
            assertEquals("PUBLIC", result.getExecutionContext().getSchemaName());
            assertEquals("TARGET_SCHEMA", connection.getSchema());
            assertMetrics(result, 0);
        }
    }

    @Test
    void statementDurationUsesLatestCumulativeResultDuration() {
        assertEquals(15L, TestSQLExecutor.statementDuration(List.of(
                ExecuteResponse.builder().duration(10L).build(),
                ExecuteResponse.builder().duration(15L).build())));
    }

    private static Connection openDatabase(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (id INT PRIMARY KEY, payload_text VARCHAR(32))");
            statement.execute("INSERT INTO sample (id, payload_text) VALUES (1, 'one'), (2, 'two')");
        }
        return connection;
    }

    private static Connection multiUpdateConnection(int... updateCounts) {
        int[] index = {-1};
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        index[0] = 0;
                        yield false;
                    }
                    case "getUpdateCount" -> index[0] >= 0 && index[0] < updateCounts.length
                            ? updateCounts[index[0]]
                            : -1;
                    case "getMoreResults" -> {
                        index[0]++;
                        yield false;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName())
                        ? statement
                        : defaultValue(method.getReturnType()));
    }

    private static Connection contextConnection(String[] catalog, String[] schema) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCatalog" -> catalog[0];
                    case "setCatalog" -> {
                        catalog[0] = (String) args[0];
                        yield null;
                    }
                    case "getSchema" -> schema[0];
                    case "setSchema" -> {
                        schema[0] = (String) args[0];
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static SimpleSqlStatement useDatabaseStatement(String databaseName) {
        RefreshTarget refreshTarget = new RefreshTarget();
        refreshTarget.setDatabaseName(databaseName);
        SimpleSqlStatement statement = new SimpleSqlStatement("USE " + databaseName);
        statement.setSqlType(ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.USE_DATABASE.name());
        statement.setRefreshTargets(List.of(refreshTarget));
        return statement;
    }

    private static SimpleSqlStatement setSchemaStatement(String schemaName) {
        RefreshTarget refreshTarget = new RefreshTarget();
        refreshTarget.setSchemaName(schemaName);
        SimpleSqlStatement statement = new SimpleSqlStatement("SET SCHEMA " + schemaName);
        statement.setSqlType(ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.SET_SCHEMA.name());
        statement.setRefreshTargets(List.of(refreshTarget));
        return statement;
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

    private static SqlExecuteRequest request(String script) {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setScript(script);
        request.setConsoleId(1L);
        request.setDataSourceId(101L);
        request.setDatabaseName("");
        request.setSchemaName("PUBLIC");
        request.setPageNo(1);
        request.setPageSize(10);
        request.setErrorContinue(Boolean.TRUE);
        return request;
    }

    private static void assertMetrics(ExecuteResponse result, int expectedRowCount) {
        ExecutionMetrics metrics = result.getExecutionMetrics();
        assertNotNull(metrics);
        assertNotNull(metrics.getStartedAtEpochMs());
        assertNotNull(metrics.getFinishedAtEpochMs());
        assertTrue(metrics.getFinishedAtEpochMs() >= metrics.getStartedAtEpochMs());
        assertEquals(expectedRowCount, metrics.getFetchedRowCount());
        assertNotNull(metrics.getTotalDurationMs());
        assertNotNull(metrics.getExecuteDurationMs());
        assertNotNull(metrics.getFetchDurationMs());
        assertEquals(metrics.getTotalDurationMs(),
                metrics.getExecuteDurationMs() + metrics.getFetchDurationMs());
        assertTrue(metrics.getExecuteDurationMs() >= 0L);
        assertTrue(metrics.getFetchDurationMs() >= 0L);
    }

    private static void putContext(Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(101L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("");
        connectInfo.setSchemaName("PUBLIC");
        connectInfo.setConnection(connection);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    private static final class CapturingConsumer implements ISqlExecutionResultConsumer {

        private final List<ExecuteResponse> finishedResults = new ArrayList<>();

        @Override
        public void statementStarted(String sql, String originalSql, String comment) {
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

    private static final class NoOpStatementListener implements ISqlExecutionStatementListener {

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }

    private static class TestSQLExecutor extends DefaultSQLExecutor {

        private static long statementDuration(List<ExecuteResponse> results) {
            return maximumStatementDuration(results);
        }

        private List<ExecuteResponse> executeMultiResults(Connection connection) throws Exception {
            return executeMulti(new SimpleSqlStatement("multi update"), connection, true,
                    null, null, null);
        }

        private void advanceContext(JdbcExecutionContext.Cursor cursor, SimpleSqlStatement statement,
                                    Connection connection, List<ExecuteResponse> executeResults) {
            advanceExecutionContext(cursor, connection, statement, executeResults);
        }

        @Override
        protected List<SimpleSqlStatement> buildSimpleSqlStatements(SqlExecuteRequest command, DbType dbType,
                                                                     String type, DBConfig dbConfig) {
            return Arrays.stream(command.getScript().split(";"))
                    .map(String::trim)
                    .filter(sql -> !sql.isEmpty())
                    .map(SimpleSqlStatement::new)
                    .toList();
        }
    }

    private static final class FailingPagingExecutor extends TestSQLExecutor {

        private int attempts;

        @Override
        protected List<ExecuteResponse> executeMulti(SimpleSqlStatement simpleSqlStatement, Connection connection,
                                                     boolean limitRowSize, Integer offset, Integer count,
                                                     Integer resultSetId, ExecutionContext executionContext)
                throws SQLException {
            attempts++;
            try {
                Thread.sleep(35L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", exception);
            }
            throw new SQLException("controlled failure " + attempts);
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final DBConfig dbConfig;
        private final IDbMetaData metaData = new DefaultMetaService();
        private final TestDbManager dbManager = new TestDbManager();

        private TestPlugin() {
            dbConfig = new DBConfig();
            dbConfig.setDbType(TEST_DB_TYPE);
            dbConfig.setSupportDatabase(true);
        }

        @Override
        public DBConfig getDBConfig() {
            return dbConfig;
        }

        @Override
        public IDbMetaData getDbMetaData() {
            return metaData;
        }

        @Override
        public IDbManager getDbManager() {
            return dbManager;
        }
    }

    private static final class TestDbManager extends DefaultDBManager {

        private Connection connection;
        private String databaseName;

        @Override
        public void connectDatabase(Connection connection, String databaseName) {
            this.connection = connection;
            this.databaseName = databaseName;
        }

        private void clear() {
            connection = null;
            databaseName = null;
        }
    }
}
