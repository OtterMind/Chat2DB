package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.SqlParameterParser;
import ai.chat2db.spi.util.SqlParameterSyntax;
import ai.chat2db.spi.util.SqlUtils;
import com.alibaba.druid.DbType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that SQL editor parameters reach the database through
 * {@link PreparedStatement} binding and never through the SQL text.
 */
class DefaultSQLExecutorParameterBindingTest {

    private static final String TEST_DB_TYPE = "H2";

    private static final String HOSTILE_VALUE = "123' OR '1'='1";

    private final DefaultSQLExecutor executor = new ScriptSplittingExecutor();

    private IPlugin previousPlugin;

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
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin());
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
    void namedParameterIsSentAsPreparedStatementValue() throws Exception {
        try (Connection database = openDatabase("named_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            List<ExecuteResponse> results = executor.execute(named("SELECT name FROM users WHERE id = :id",
                    Map.of("id", SqlParameterValue.number("123"))));

            assertEquals(List.of(List.of("alice")), values(results.get(0)));
            assertTrue(results.get(0).getSuccess());
            assertEquals("SELECT name FROM users WHERE id = :id", results.get(0).getOriginalSql(),
                    "results report the SQL as written so paging can rerun it with the same parameters");
            assertPreparedWithoutValue(recording, "123");
            assertTrue(recording.bindings.contains("setBigDecimal(1, 123)"), recording.bindings.toString());
        }
    }

    @Test
    void duplicateNamedParameterBindsOneValueToEveryOccurrence() throws Exception {
        try (Connection database = openDatabase("duplicate_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            List<ExecuteResponse> results = executor.execute(named(
                    "SELECT name FROM users WHERE id = :id OR manager_id = :id ORDER BY name",
                    Map.of("id", SqlParameterValue.number("123"))));

            assertEquals(List.of(List.of("alice"), List.of("carol")), values(results.get(0)));
            assertTrue(recording.bindings.containsAll(List.of("setBigDecimal(1, 123)", "setBigDecimal(2, 123)")),
                    recording.bindings.toString());
            assertPreparedWithoutValue(recording, "123");
        }
    }

    @Test
    void positionalParametersBindInOrderAndReportTheSqlAsWritten() throws Exception {
        try (Connection database = openDatabase("positional_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());
            String sql = "SELECT name FROM users WHERE name <> ? AND id > ? ORDER BY id";

            List<ExecuteResponse> results = executor.execute(positional(sql,
                    SqlParameterValue.string("carol"), SqlParameterValue.number("100")));

            assertEquals(List.of(List.of("alice")), values(results.get(0)));
            assertEquals(List.of("setString(1, carol)", "setBigDecimal(2, 100)"), recording.bindings);
            assertEquals(sql, results.get(0).getOriginalSql());
            assertFalse(results.get(0).getSql().contains(SqlParameterParser.POSITIONAL_NAME_PREFIX),
                    results.get(0).getSql());
            assertPreparedWithoutValue(recording, "carol");
        }
    }

    @Test
    void typedValuesUseTheirOwnBindCall() throws Exception {
        try (Connection database = openDatabase("typed_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            List<ExecuteResponse> results = executor.execute(named(
                    "SELECT COUNT(*) FROM users WHERE :flag AND id >= :min",
                    Map.of("flag", SqlParameterValue.bool("true"), "min", SqlParameterValue.number("7.0"))));

            assertEquals(List.of(List.of("3")), values(results.get(0)));
            assertEquals(List.of("setBoolean(1, true)", "setBigDecimal(2, 7.0)"), recording.bindings);
        }
    }

    @Test
    void injectionAttemptIsComparedAsPlainValue() throws Exception {
        try (Connection database = openDatabase("injection_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            List<ExecuteResponse> results = executor.execute(named("SELECT name FROM users WHERE name = :name",
                    Map.of("name", SqlParameterValue.string(HOSTILE_VALUE))));

            assertTrue(results.get(0).getSuccess());
            assertEquals(List.of(), values(results.get(0)));
            assertPreparedWithoutValue(recording, HOSTILE_VALUE);
        }
    }

    @Test
    void nullValueBindsSqlNullAndEmptyStringStaysEmpty() throws Exception {
        try (Connection database = openDatabase("null_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            List<ExecuteResponse> results = executor.execute(named(
                    "SELECT COUNT(*) FROM users WHERE nickname IS NOT DISTINCT FROM :nickname OR name = :name",
                    Map.of("nickname", SqlParameterValue.nullValue(), "name", SqlParameterValue.string(""))));

            assertEquals(List.of(List.of("2")), values(results.get(0)));
            assertEquals(List.of("setNull(1, " + Types.VARCHAR + ")", "setString(2, )"), recording.bindings);
        }
    }

    @Test
    void pagingRebindsAgainstThePagedSql() throws Exception {
        try (Connection database = openDatabase("paged_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());
            SqlExecuteRequest request = named("SELECT name FROM users WHERE id > :min ORDER BY id",
                    Map.of("min", SqlParameterValue.number("0")));
            request.setPageNo(2);
            request.setPageSize(1);

            List<ExecuteResponse> results = executor.execute(request);

            assertEquals(List.of(List.of("alice")), values(results.get(0)));
            assertEquals(1, recording.preparedSql.size(), recording.preparedSql.toString());
            assertTrue(recording.preparedSql.get(0).toUpperCase().contains("LIMIT"), recording.preparedSql.toString());
            assertEquals("SELECT name FROM users WHERE id > :min ORDER BY id", results.get(0).getOriginalSql());
            assertPreparedWithoutValue(recording, ":min");
        }
    }

    @Test
    void countRewriteBindsOnlyTheParametersItKeeps() throws Exception {
        try (Connection database = openDatabase("count_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());
            SqlParameterParser.NormalizedSql normalized = SqlParameterParser.normalize(
                    "SELECT name, ? AS tag FROM users WHERE id > ? ORDER BY ?", null,
                    List.of(SqlParameterValue.string("x"), SqlParameterValue.number("100"),
                            SqlParameterValue.number("1")), SqlParameterSyntax.ANSI);
            String countSql = SqlUtils.count(normalized.sql(), TEST_DB_TYPE);

            ExecuteResponse response = executor.execute(SqlStatementExecuteRequest.builder()
                    .sql(countSql)
                    .connection(recording.proxy())
                    .limitRowSize(true)
                    .parameters(normalized.parameters())
                    .build());

            assertEquals(List.of(List.of("2")), response.getDisplayDataList(), countSql);
            assertEquals(List.of("setBigDecimal(1, 100)"), recording.bindings, countSql);
        }
    }

    @Test
    void rowByRowCountFallbackBindsParameters() throws Exception {
        try (Connection database = openDatabase("count_fallback_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            Long count = executor.count("SELECT name FROM users WHERE id > :min", recording.proxy(),
                    Map.of("min", SqlParameterValue.number("100")));

            assertEquals(2L, count);
            assertEquals(List.of("setBigDecimal(1, 100)"), recording.bindings);
            assertPreparedWithoutValue(recording, ":min");
        }
    }

    @Test
    void streamingExecutionBindsParametersToo() throws Exception {
        try (Connection database = openDatabase("streaming_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());
            CapturingConsumer consumer = new CapturingConsumer();

            executor.executeStreaming(positional("SELECT name FROM users WHERE id = ?",
                    SqlParameterValue.number("123")), consumer, new NoOpStatementListener(), () -> false);

            assertEquals(1, consumer.finishedResults.size());
            assertTrue(consumer.finishedResults.get(0).getSuccess());
            assertEquals(List.of("SELECT name FROM users WHERE id = ?"), consumer.startedOriginalSql);
            assertEquals("SELECT name FROM users WHERE id = ?", consumer.finishedResults.get(0).getOriginalSql());
            assertPreparedWithoutValue(recording, "123");
            assertEquals(List.of("setBigDecimal(1, 123)"), recording.bindings);
        }
    }

    @Test
    void sqlWithoutParametersRunsExactlyAsBefore() throws Exception {
        try (Connection database = openDatabase("plain_execution")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            List<ExecuteResponse> results = executor.execute(named("SELECT name FROM users WHERE id = 123", Map.of()));

            assertEquals(List.of(List.of("alice")), values(results.get(0)));
            assertEquals("SELECT name FROM users WHERE id = 123", results.get(0).getOriginalSql());
            assertEquals(List.of(), recording.bindings);
        }
    }

    @Test
    void parametersAreRejectedForMultipleStatements() throws Exception {
        try (Connection database = openDatabase("multi_statement_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            BusinessException exception = assertThrows(BusinessException.class, () -> executor.execute(named(
                    "SELECT name FROM users WHERE id = :id; SELECT name FROM users WHERE manager_id = :id",
                    Map.of("id", SqlParameterValue.number("123")))));

            assertEquals("sqlParameter.multipleStatements", exception.getCode());
            assertEquals(List.of(), recording.preparedSql, "nothing may reach the database");
        }
    }

    @Test
    void invalidParametersAreRejectedBeforeReachingTheDatabase() throws Exception {
        try (Connection database = openDatabase("invalid_binding")) {
            RecordingConnection recording = new RecordingConnection(database);
            putContext(recording.proxy());

            BusinessException missing = assertThrows(BusinessException.class, () -> executor.execute(unchecked(
                    "SELECT * FROM users WHERE id = :id AND name = :name",
                    Map.of("id", SqlParameterValue.number("1")))));
            BusinessException mixed = assertThrows(BusinessException.class, () -> executor.execute(unchecked(
                    "SELECT * FROM users WHERE id = :id AND name = ?", Map.of("id", SqlParameterValue.number("1")))));

            assertEquals("sqlParameter.missing", missing.getCode());
            assertEquals("sqlParameter.mixedStyles", mixed.getCode());
            assertEquals(List.of(), recording.preparedSql, "nothing may reach the database");
        }
    }

    private static void assertPreparedWithoutValue(RecordingConnection recording, String value) {
        assertFalse(recording.preparedSql.isEmpty());
        for (String sql : recording.preparedSql) {
            assertFalse(sql.contains(value), () -> "value was interpolated into " + sql);
            assertFalse(sql.matches("(?s).*:[A-Za-z_].*"), () -> "named marker reached the driver: " + sql);
            assertTrue(sql.contains("?"), () -> "expected a JDBC placeholder in " + sql);
        }
    }

    private static List<List<String>> values(ExecuteResponse response) {
        List<List<String>> rows = new ArrayList<>();
        for (List<ResultCell> row : response.getDataList()) {
            List<String> values = new ArrayList<>();
            // The first column is the row number added by the executor.
            for (ResultCell cell : row.subList(1, row.size())) {
                values.add(cell.getValue());
            }
            rows.add(values);
        }
        return rows;
    }

    private static Connection openDatabase(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INT PRIMARY KEY, manager_id INT, name VARCHAR(32), "
                    + "nickname VARCHAR(32))");
            statement.execute("INSERT INTO users VALUES (123, NULL, 'alice', NULL), (7, NULL, 'bob', 'b'), "
                    + "(200, 123, 'carol', NULL)");
        }
        return connection;
    }

    /**
     * Builds the executor command the way the domain service does: values are
     * validated and normalised against the SQL first.
     */
    private static SqlExecuteRequest named(String script, Map<String, SqlParameterValue> parameters) {
        return normalized(script, SqlParameterParser.normalize(script, parameters, null, SqlParameterSyntax.ANSI));
    }

    private static SqlExecuteRequest positional(String script, SqlParameterValue... parameters) {
        return normalized(script, SqlParameterParser.normalize(script, null, List.of(parameters),
                SqlParameterSyntax.ANSI));
    }

    /**
     * Builds a command whose values were not checked, to prove the executor
     * guards the database on its own.
     */
    private static SqlExecuteRequest unchecked(String script, Map<String, SqlParameterValue> parameters) {
        SqlExecuteRequest request = request(script);
        request.setParameters(parameters);
        return request;
    }

    private static SqlExecuteRequest normalized(String script, SqlParameterParser.NormalizedSql normalizedSql) {
        if (normalizedSql == null) {
            return request(script);
        }
        SqlExecuteRequest request = request(normalizedSql.sql());
        request.setParameters(normalizedSql.parameters());
        request.setPositionalParameterStyle(normalizedSql.positional());
        return request;
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

    /**
     * Delegates to a real H2 connection and records the SQL text handed to the
     * driver plus every parameter binding call.
     */
    private static final class RecordingConnection {

        private final Connection delegate;

        private final List<String> preparedSql = new ArrayList<>();

        private final List<String> bindings = new ArrayList<>();

        private RecordingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        private Connection proxy() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(delegate, method, args);
                        if ("prepareStatement".equals(method.getName())) {
                            preparedSql.add((String) args[0]);
                            return recordingStatement((PreparedStatement) result);
                        }
                        return result;
                    });
        }

        private PreparedStatement recordingStatement(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("set") && args != null && args.length >= 2
                                && args[0] instanceof Integer) {
                            bindings.add(name + "(" + args[0] + ", " + args[1] + ")");
                        }
                        return invoke(statement, method, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private static final class CapturingConsumer implements ISqlExecutionResultConsumer {

        private final List<ExecuteResponse> finishedResults = new ArrayList<>();

        private final List<String> startedOriginalSql = new ArrayList<>();

        private final List<String> startedSql = new ArrayList<>();

        @Override
        public void statementStarted(String sql, String originalSql, String comment) {
            startedOriginalSql.add(originalSql);
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

    private static final class NoOpStatementListener implements ISqlExecutionStatementListener {

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }

    /**
     * Splits on {@code ;} so the test does not depend on a dialect parser plugin.
     */
    private static final class ScriptSplittingExecutor extends DefaultSQLExecutor {

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

    private static final class TestPlugin implements IPlugin {

        private final DBConfig dbConfig;

        private final IDbMetaData metaData = new DefaultMetaService();

        private final IDbManager dbManager = new DefaultDBManager() {
            @Override
            public void connectDatabase(Connection connection, String databaseName) {
            }
        };

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
}
