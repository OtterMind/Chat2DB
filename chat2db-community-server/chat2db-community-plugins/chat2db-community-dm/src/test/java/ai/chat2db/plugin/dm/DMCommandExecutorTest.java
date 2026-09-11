package ai.chat2db.plugin.dm;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionContext;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.plugin.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionCancellation;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.plugin.dm.builder.DMSqlBuilder;
import ai.chat2db.plugin.dm.parser.DMExecutableSql;
import ai.chat2db.plugin.dm.parser.DMSqlParser;
import dm.jdbc.driver.DmdbConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DMCommandExecutorTest {

    private static final long DATA_SOURCE_ID = 2762L;

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
            Chat2DBContext.PLUGIN_MAP.remove("DM");
        } else {
            Chat2DBContext.PLUGIN_MAP.put("DM", previousPlugin);
        }
        contextBound = false;
        previousPlugin = null;
    }

    @Test
    void allDmSqlShouldUseDmExecutor() {
        assertSame(DMCommandExecutor.INSTANCE, new DMMetaData().getCommandExecutor());
    }

    @Test
    void parserShouldRecognizeExplicitExplainAndReturnInnerSql() {
        DMExecutableSql result = new DMSqlParser().parseExecutableSql("EXPLAIN SELECT * FROM SYSOBJECTS");

        assertTrue(result.isExplain());
        assertEquals("SELECT * FROM SYSOBJECTS", result.executableSql());
    }

    @Test
    void builderShouldNotAddExplainTwice() {
        assertEquals("EXPLAIN SELECT * FROM SYSOBJECTS",
                new DMSqlBuilder().buildExplain("EXPLAIN SELECT * FROM SYSOBJECTS"));
    }

    @Test
    void ordinarySqlShouldDelegateToDefaultExecutionInsideDmExecutor() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:dm_executor_select")) {
            putContext(connection);
            List<ExecuteResponse> results = executeMulti("SELECT 1 AS ID", connection);

            assertEquals(1, results.size());
            assertEquals("1", results.get(0).getDataList().get(0).get(0).getValue());
        }
    }

    @Test
    void explainShouldCallGetExplainInfoInsteadOfPreparedStatementExecute() throws Exception {
        Connection connection = dmExplainConnection();

        List<ExecuteResponse> results = executeMulti("EXPLAIN SELECT * FROM SYSOBJECTS", connection);

        assertEquals(1, results.size());
        assertEquals("plan for: SELECT * FROM SYSOBJECTS", results.get(0).getDataList().get(0).get(0).getValue());
    }

    @Test
    void explainButtonShouldBuildExplainOnceAndCallGetExplainInfo() throws Exception {
        Connection connection = dmExplainConnection();
        putContext(connection);

        SqlExecuteRequest request = request("SELECT * FROM SYSOBJECTS");
        request.setExplain(true);

        List<ExecuteResponse> results = DMCommandExecutor.INSTANCE.execute(request);

        assertEquals(1, results.size());
        assertEquals(ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.EXPLAIN.name(),
                results.get(0).getSqlType());
        // non-streaming path will add row-number, so plan is in the 2nd column.
        assertEquals("plan for: SELECT * FROM SYSOBJECTS", results.get(0).getDataList().get(0).get(1).getValue());
        assertNotNull(results.get(0).getExecutionMetrics());
        assertNotNull(results.get(0).getExecutionMetrics().getTotalDurationMs());
        assertEquals(1, results.get(0).getExecutionMetrics().getFetchedRowCount());
    }

    @Test
    void explainButtonStreamingShouldKeepSqlTypeExplain() throws Exception {
        Connection connection = dmExplainConnection();
        putContext(connection);

        SqlExecuteRequest request = request("SELECT * FROM SYSOBJECTS");
        request.setExplain(true);
        CapturingResultConsumer consumer = new CapturingResultConsumer();

        DMCommandExecutor.INSTANCE.executeStreaming(request, consumer, null, () -> false);

        assertEquals(1, consumer.resultStartedCount);
        assertEquals(0, consumer.resultStartedRowCount);
        assertEquals(1, consumer.rowsEventCount);
        assertEquals(1, consumer.receivedRows.size());
        assertEquals("plan for: SELECT * FROM SYSOBJECTS", consumer.receivedRows.get(0).get(1).getValue());
        assertEquals(1, consumer.resultFinished.size());
        assertEquals(ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.EXPLAIN.name(),
                consumer.resultFinished.get(0).getSqlType());
        assertNotNull(consumer.resultFinished.get(0).getExecutionMetrics());
        assertEquals(1, consumer.resultFinished.get(0).getExecutionMetrics().getFetchedRowCount());
    }

    @Test
    void shouldStreamExplainPlanWithRowNumber() throws Exception {
        Connection connection = dmExplainConnection();
        putContext(connection);

        SqlExecuteRequest request = request("EXPLAIN SELECT * FROM SYSOBJECTS");
        CapturingResultConsumer consumer = new CapturingResultConsumer();

        DMCommandExecutor.INSTANCE.executeStreaming(request, consumer, null, () -> false);

        // Outer executeStreaming owns the single resultFinished; publishMaterializedQueryResult must not duplicate it.
        assertEquals(1, consumer.resultFinished.size());
        assertEquals(1, consumer.resultStartedCount);
        assertEquals(0, consumer.resultStartedRowCount);
        assertEquals(1, consumer.rowsEventCount);
        assertEquals(1, consumer.receivedRows.size());
        assertEquals("plan for: SELECT * FROM SYSOBJECTS", consumer.receivedRows.get(0).get(1).getValue());

        ExecuteResponse response = consumer.resultFinished.get(0);
        assertEquals(ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum.EXPLAIN.name(), response.getSqlType());
        assertTrue(response.getHeaderList().size() >= 2);
        assertEquals("Execution Plan", response.getHeaderList().get(response.getHeaderList().size() - 1).getName());
        assertEquals("plan for: SELECT * FROM SYSOBJECTS", response.getDataList().get(0).get(1).getValue());
        assertNotNull(response.getExecutionMetrics());
        assertNotNull(response.getExecutionMetrics().getTotalDurationMs());
        assertEquals(1, response.getExecutionMetrics().getFetchedRowCount());
    }

    @Test
    void ordinarySqlShouldSkipFullAntlrParseAtExecutorEntry() {
        DMExecutableSql parsed = new DMSqlParser().parseExecutableSql("SELECT 1 AS ID FROM DUAL");

        assertTrue(!parsed.isExplain());
        assertEquals("SELECT 1 AS ID FROM DUAL", parsed.executableSql());
    }

    @Test
    void invalidExplainShouldNotFallBackToJdbcPath() {
        assertThrows(IllegalArgumentException.class,
                () -> new DMSqlParser().parseExecutableSql("EXPLAIN PLAN FOR SELECT 1 FROM DUAL"));
    }

    @Test
    void explainShouldWorkThroughStatementGuardAndConnectionOnlyWrapper() throws Exception {
        List<String> guardedSql = new ArrayList<>();
        Connection dmdb = dmExplainConnection();
        Connection poolWrapped = connectionOnlyWrapper(dmdb);
        putContext(poolWrapped);

        try (Chat2DBContext.StatementGuardScope ignored = Chat2DBContext.bindStatementGuard(guardedSql::add)) {
            SqlExecuteRequest request = request("EXPLAIN SELECT * FROM SYSOBJECTS");
            List<ExecuteResponse> results = DMCommandExecutor.INSTANCE.execute(request);

            assertEquals(1, results.size());
            assertEquals("plan for: SELECT * FROM SYSOBJECTS", results.get(0).getDataList().get(0).get(1).getValue());
            assertTrue(guardedSql.contains("EXPLAIN SELECT * FROM SYSOBJECTS"));
        }
    }

    @Test
    void shouldHandleMultiStatementsWithExplainAndNormalSelect() throws Exception {
        try (Connection h2Connection = DriverManager.getConnection("jdbc:h2:mem:dm_multi_statement")) {
            Connection hybrid = dmHybridConnection(h2Connection);
            putContext(hybrid);

            SqlExecuteRequest request = request("EXPLAIN SELECT * FROM SYSOBJECTS; SELECT 1 AS ID");
            request.setExplain(false);
            request.setSingle(false);

            List<ExecuteResponse> results = DMCommandExecutor.INSTANCE.execute(request);

            // Should return at least 2 execute results: one EXPLAIN plan and one SELECT result.
            assertTrue(results.size() >= 2);

            String explainPlan = results.stream()
                    .flatMap(r -> r.getDataList().stream())
                    .filter(row -> row.size() >= 2 && "plan for: SELECT * FROM SYSOBJECTS".equals(row.get(1).getValue()))
                    .map(row -> row.get(1).getValue())
                    .findFirst()
                    .orElse(null);
            assertEquals("plan for: SELECT * FROM SYSOBJECTS", explainPlan);

            String selectValue = results.stream()
                    .flatMap(r -> r.getDataList().stream())
                    .filter(row -> row.size() >= 2 && "1".equals(row.get(1).getValue()))
                    .map(row -> row.get(1).getValue())
                    .findFirst()
                    .orElse(null);
            assertEquals("1", selectValue);
        }
    }

    @Test
    void streamingCancellationShouldStopAfterSecondCancellationCheck() throws Exception {
        Connection connection = dmExplainConnection();

        CapturingResultConsumer consumer = new CapturingResultConsumer();
        AtomicInteger streamResultSequence = new AtomicInteger(0);
        ISqlExecutionCancellation cancellation = () -> streamResultSequence.incrementAndGet() >= 2;

        SimpleSqlStatement statement = new SimpleSqlStatement("EXPLAIN SELECT * FROM SYSOBJECTS");

        var method = DMCommandExecutor.class.getDeclaredMethod("executeMultiStreaming",
                SimpleSqlStatement.class,
                Connection.class,
                boolean.class,
                Integer.class,
                Integer.class,
                Integer.class,
                ISqlExecutionResultConsumer.class,
                ISqlExecutionStatementListener.class,
                ISqlExecutionCancellation.class,
                SqlTypeEnum.class,
                String.class,
                int.class,
                int.class,
                AtomicInteger.class,
                int.class,
                ExecutionContext.class);
        method.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> method.invoke(DMCommandExecutor.INSTANCE,
                        statement,
                        connection,
                        true,
                        0,
                        10,
                        null,
                        consumer,
                        null,
                        cancellation,
                        null,
                        statement.getSql(),
                        1,
                        10,
                        streamResultSequence,
                        1,
                        null));
        assertTrue(exception.getCause() instanceof SQLException);
        assertEquals(0, consumer.resultFinished.size());
        assertEquals(0, consumer.rowsEventCount);
    }

    @Test
    void explainShouldResolveDmdbConnectionFromIndependentDriverClassLoader() throws Exception {
        try (URLClassLoader driverClassLoader = newDriverIsolatingClassLoader()) {
            Class<?> isolatedDmConnection = Class.forName(
                    "dm.jdbc.driver.DmdbConnection", true, driverClassLoader);
            assertNotSame(DmdbConnection.class, isolatedDmConnection,
                    "driver ClassLoader must define its own DmdbConnection type");

            Connection connection = (Connection) Proxy.newProxyInstance(
                    driverClassLoader,
                    new Class<?>[]{Connection.class, isolatedDmConnection},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "unwrap" -> unwrap(proxy, (Class<?>) args[0]);
                        case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                        case "getExplainInfo" -> "isolated-plan:" + args[0];
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });

            assertNotSame(getClass().getClassLoader(), connection.getClass().getClassLoader());
            putContext(connection);

            List<ExecuteResponse> results = DMCommandExecutor.INSTANCE.execute(
                    request("EXPLAIN SELECT * FROM SYSOBJECTS"));

            assertEquals(1, results.size());
            assertEquals("isolated-plan:SELECT * FROM SYSOBJECTS",
                    results.get(0).getDataList().get(0).get(1).getValue());
        }
    }

    @Test
    void explainDriverSqlExceptionShouldSurfaceOnNonStreamingPath() throws Exception {
        Connection connection = dmExplainConnectionThrowing(
                new SQLException("DM-EXPLAIN-ERR-2762", "HY000", 2762));
        putContext(connection);

        List<ExecuteResponse> results = DMCommandExecutor.INSTANCE.execute(
                request("EXPLAIN SELECT * FROM SYSOBJECTS"));

        assertEquals(1, results.size());
        assertEquals(Boolean.FALSE, results.get(0).getSuccess());
        assertTrue(results.get(0).getMessage().contains("DM-EXPLAIN-ERR-2762"));
    }

    @Test
    void explainDriverSqlExceptionShouldFinishStreamingPath() throws Exception {
        Connection connection = dmExplainConnectionThrowing(
                new SQLException("DM-EXPLAIN-ERR-STREAM", "HY000", 2763));
        putContext(connection);

        CapturingResultConsumer consumer = new CapturingResultConsumer();
        DMCommandExecutor.INSTANCE.executeStreaming(
                request("EXPLAIN SELECT * FROM SYSOBJECTS"), consumer, null, () -> false);

        assertEquals(0, consumer.resultStartedCount);
        assertEquals(0, consumer.rowsEventCount);
        assertEquals(1, consumer.statementFinishedCount);
        assertEquals(1, consumer.resultFinished.size());
        ExecuteResponse finished = consumer.resultFinished.get(0);
        assertEquals(Boolean.FALSE, finished.getSuccess());
        assertTrue(finished.getMessage().contains("DM-EXPLAIN-ERR-STREAM"));
    }

    @SuppressWarnings("unchecked")
    private List<ExecuteResponse> executeMulti(String sql, Connection connection) throws Exception {
        Method method = DMCommandExecutor.class.getDeclaredMethod("executeMulti", SimpleSqlStatement.class,
                Connection.class, boolean.class, Integer.class, Integer.class, Integer.class,
                ai.chat2db.community.domain.api.model.result.ExecutionContext.class);
        method.setAccessible(true);
        return (List<ExecuteResponse>) method.invoke(DMCommandExecutor.INSTANCE,
                new SimpleSqlStatement(sql), connection, true, 0, 10, null, null);
    }

    private Connection dmExplainConnection() {
        return dmExplainConnection(null);
    }

    private Connection dmExplainConnectionThrowing(SQLException failure) {
        return dmExplainConnection(failure);
    }

    private Connection dmExplainConnection(SQLException failure) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class, DmdbConnection.class},
                (proxy, method, args) -> {
                    if ("getExplainInfo".equals(method.getName())) {
                        if (failure != null) {
                            throw failure;
                        }
                        return "plan for: " + args[0];
                    }
                    return switch (method.getName()) {
                        case "unwrap" -> unwrap(proxy, (Class<?>) args[0]);
                        case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    /**
     * Child-first loader that defines {@code dm.jdbc.driver.*} from its own URLs,
     * mimicking Chat2DB's independent JDBC driver ClassLoader.
     */
    private URLClassLoader newDriverIsolatingClassLoader() throws Exception {
        URL classResource = getClass().getClassLoader().getResource("dm/jdbc/driver/DmdbConnection.class");
        assertNotNull(classResource, "test stub DmdbConnection.class must be on the test classpath");

        Path root = Files.createTempDirectory("dm-driver-isolation");
        Path packageDir = root.resolve("dm/jdbc/driver");
        Files.createDirectories(packageDir);
        try (var in = classResource.openStream()) {
            Files.copy(in, packageDir.resolve("DmdbConnection.class"));
        }

        return new URLClassLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dm.jdbc.driver.")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private Connection dmHybridConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class, DmdbConnection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getExplainInfo".equals(name)) {
                        return "plan for: " + args[0];
                    }
                    if ("unwrap".equals(name)) {
                        Class<?> target = (Class<?>) args[0];
                        if (target == Connection.class || target.isInstance(proxy) || target == DmdbConnection.class) {
                            return proxy;
                        }
                        return delegate.unwrap(target);
                    }
                    if ("isWrapperFor".equals(name)) {
                        Class<?> target = (Class<?>) args[0];
                        return target == Connection.class || target == DmdbConnection.class
                                || target.isInstance(proxy) || delegate.isWrapperFor(target);
                    }
                    if ("isClosed".equals(name)) {
                        return delegate.isClosed();
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "DMHybridConnection";
                            default -> throw new IllegalStateException("Unexpected Object method: " + name);
                        };
                    }
                    return method.invoke(delegate, args);
                });
    }

    private Connection connectionOnlyWrapper(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("unwrap".equals(name)) {
                        Class<?> target = (Class<?>) args[0];
                        if (target == Connection.class) {
                            return proxy;
                        }
                        if (target.isInstance(delegate) || target == DmdbConnection.class) {
                            return delegate;
                        }
                        return delegate.unwrap(target);
                    }
                    if ("isWrapperFor".equals(name)) {
                        Class<?> target = (Class<?>) args[0];
                        return target == Connection.class
                                || target.isInstance(delegate)
                                || target == DmdbConnection.class
                                || delegate.isWrapperFor(target);
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "ConnectionOnlyWrapper";
                            default -> throw new IllegalStateException("Unexpected Object method: " + name);
                        };
                    }
                    return method.invoke(delegate, args);
                });
    }

    private void putContext(Connection connection) {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put("DM", new DMPlugin());
        contextBound = true;
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(DATA_SOURCE_ID);
        connectInfo.setConsoleId(1L);
        connectInfo.setDbType("DM");
        connectInfo.setDatabaseName("");
        connectInfo.setSchemaName("SYSDBA");
        connectInfo.setUrl("jdbc:test:dm");
        connectInfo.setConnection(connection);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("DM");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    private SqlExecuteRequest request(String sql) {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setScript(sql);
        request.setConsoleId(1L);
        request.setDataSourceId(DATA_SOURCE_ID);
        request.setDatabaseName("");
        request.setSchemaName("SYSDBA");
        request.setPageNo(1);
        request.setPageSize(10);
        request.setErrorContinue(Boolean.TRUE);
        return request;
    }

    private Object unwrap(Object proxy, Class<?> targetClass) throws SQLException {
        if (targetClass.isInstance(proxy) || targetClass == Connection.class) {
            return proxy;
        }
        throw new SQLException("Unsupported unwrap target: " + targetClass.getName());
    }

    private Object defaultValue(Class<?> returnType) {
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

    private static final class CapturingResultConsumer implements ISqlExecutionResultConsumer {

        private int resultStartedCount;
        /** Row count carried on resultStarted; should stay 0 for materialized explain streaming. */
        private int resultStartedRowCount;
        private int rowsEventCount;
        private int statementFinishedCount;
        private final List<List<ResultCell>> receivedRows = new ArrayList<>();
        private final List<ExecuteResponse> resultFinished = new ArrayList<>();

        @Override
        public void statementStarted(String sql, String originalSql, String comment) {
        }

        @Override
        public void resultStarted(ExecuteResponse result) {
            resultStartedCount++;
            resultStartedRowCount = result.getDataList() == null ? 0 : result.getDataList().size();
        }

        @Override
        public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
            rowsEventCount++;
            receivedRows.addAll(rows);
        }

        @Override
        public void resultFinished(ExecuteResponse result) {
            resultFinished.add(result);
        }

        @Override
        public void updateCount(ExecuteResponse result) {
        }

        @Override
        public void statementFinished(String sql, long duration) {
            statementFinishedCount++;
        }
    }
}
