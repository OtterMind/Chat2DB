package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.model.value.ResultValueBudget;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSQLExecutorLargeCellTest {

    private static final String TEST_DB_TYPE = "SQL_EXECUTOR_LARGE_CELL_TEST_H2";

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
                if (codes != null && codes.length > 0) {
                    return codes[0];
                }
                return resolvable.getDefaultMessage();
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
    void resultGridKeepsLargeTextBoundedAndReturnsCellMetadata() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:sql_executor_large_cell;DB_CLOSE_DELAY=-1")) {
            putContext(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE doc (id INT PRIMARY KEY, content CLOB)");
            }
            try (var statement = connection.prepareStatement("INSERT INTO doc (id, content) VALUES (?, ?)")) {
                statement.setInt(1, 1);
                statement.setCharacterStream(2, new StringReader("x".repeat(20 * 1024 * 1024)), 20 * 1024 * 1024);
                statement.executeUpdate();
            }

            ExecuteResponse result = DefaultSQLExecutor.getInstance().execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT content FROM doc")
                    .connection(connection)
                    .limitRowSize(true)
                    .offset(0)
                    .count(1)
                    .build());

            assertEquals(1, result.getDataList().size());
            ResultCell cell = result.getDataList().get(0).get(0);
            assertTrue(cell.getValue().startsWith("[CHARACTER LARGE OBJECT] 20.00 MB"));
            assertTrue(cell.isLargeValue());
            assertTrue(cell.isTruncated());
            assertEquals(20L * 1024L * 1024L, cell.getSizeBytes());
            assertEquals("TEXT", cell.getValueType());
        }
    }

    @Test
    void v2CanReadCompleteValuesWithoutChangingTheDefaultQueryPreview() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:agent_v2_complete_values")) {
            putContext(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE doc (content CLOB)");
            }
            String body = "数据".repeat(400000);
            try (var statement = connection.prepareStatement("INSERT INTO doc VALUES (?)")) {
                statement.setCharacterStream(1, new StringReader(body), body.length());
                statement.executeUpdate();
            }
            var command = new ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest();
            command.setScript("SELECT content FROM doc");
            command.setSingle(true);
            command.setPageNo(1);
            command.setPageSize(1);
            command.setDatabaseName(connection.getCatalog());
            command.setSchemaName("PUBLIC");
            var executor = new DefaultSQLExecutor();
            var legacy = executor.execute(command).get(0);
            assertTrue(legacy.getSuccess(), legacy.getMessage());
            assertTrue(legacy.getDataList().get(0).stream().anyMatch(ResultCell::isTruncated));
            command.setFullResultValues(true);
            var complete = executor.execute(command).get(0);
            assertTrue(complete.getSuccess(), complete.getMessage());
            assertTrue(complete.getDataList().get(0).stream().anyMatch(cell -> body.equals(cell.getValue())));
            assertFalse(complete.getDataList().get(0).stream().anyMatch(ResultCell::isTruncated));
            command.setFullResultValues(false);
            assertTrue(executor.execute(command).get(0).getDataList().get(0).stream().anyMatch(ResultCell::isTruncated));
        }
    }

    @Test
    void v2SharesCaptureBudgetAcrossRowsAndPreservesRealPartialMetadata() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:agent_v2_capture_rows")) {
            putContext(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE doc (id INT, content CLOB, nullable_value CLOB)");
            }
            String body = "数据😀".repeat(10000);
            try (var statement = connection.prepareStatement("INSERT INTO doc VALUES (?, ?, NULL)")) {
                for (int i = 1; i <= 3; i++) {
                    statement.setInt(1, i);
                    statement.setCharacterStream(2, new StringReader(body), body.length());
                    statement.executeUpdate();
                }
            }
            var command = agentCommand(connection, "SELECT content, nullable_value FROM doc ORDER BY id", 3);
            long cap = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1024;
            var result = boundedExecutor(cap).execute(command).get(0);
            assertTrue(result.getSuccess(), result.getMessage());
            assertEquals(3, result.getDataList().size());
            long retained = result.getDataList().stream().flatMap(java.util.Collection::stream)
                    .filter(cell -> cell.getLoadedBytes() != null).mapToLong(ResultCell::getLoadedBytes).sum();
            assertTrue(retained <= cap);
            var first = result.getDataList().get(0).stream().filter(cell -> "TEXT".equals(cell.getValueType()) && cell.getValue() != null).findFirst().orElseThrow();
            assertEquals(body, first.getValue());
            assertFalse(first.isTruncated());
            var partial = result.getDataList().get(1).stream().filter(ResultCell::isTruncated).findFirst().orElseThrow();
            assertTrue(body.startsWith(partial.getValue()));
            org.junit.jupiter.api.Assertions.assertNull(partial.getSizeChars());
            assertTrue(partial.getUnsupportedReason().startsWith("CAPTURE_BUDGET_EXCEEDED"));
            assertFalse(partial.getValue().endsWith("\uD83D"));
            assertTrue(result.getDataList().get(2).stream().anyMatch(cell -> cell.getValue() == null && !cell.isTruncated()));
            command.setFullResultValues(false);
            var legacy = boundedExecutor(1).execute(command).get(0);
            assertTrue(legacy.getDataList().get(0).stream().anyMatch(cell -> cell.isTruncated() && cell.getUnsupportedReason() == null));
        }
    }

    @Test
    void v2BoundsVarcharAndBinaryAndRetainsScalarFormatting() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:agent_v2_capture_types")) {
            putContext(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE doc (amount DECIMAL(30,4), text_value VARCHAR, binary_value BLOB)");
            }
            try (var statement = connection.prepareStatement("INSERT INTO doc VALUES (?, ?, ?)")) {
                statement.setBigDecimal(1, new java.math.BigDecimal("9007199254740993.1200"));
                statement.setString(2, "x".repeat(50000));
                statement.setBinaryStream(3, new java.io.ByteArrayInputStream(new byte[50000]), 50000);
                statement.executeUpdate();
            }
            var text = boundedExecutor(1024).execute(agentCommand(connection, "SELECT amount, text_value FROM doc", 1)).get(0);
            assertTrue(text.getDataList().get(0).stream().anyMatch(cell -> "9007199254740993.1200".equals(cell.getValue())));
            var clippedText = text.getDataList().get(0).stream().filter(ResultCell::isTruncated).findFirst().orElseThrow();
            assertTrue(clippedText.getLoadedBytes() < 1024);
            var binary = boundedExecutor(1024).execute(agentCommand(connection, "SELECT binary_value FROM doc", 1)).get(0);
            var clippedBinary = binary.getDataList().get(0).stream().filter(ResultCell::isTruncated).findFirst().orElseThrow();
            assertTrue(clippedBinary.getValue().startsWith("0x"));
            assertEquals(1024L, clippedBinary.getLoadedBytes());
        }
    }

    private static DefaultSQLExecutor boundedExecutor(long bytes) {
        return new DefaultSQLExecutor() {
            @Override protected ResultValueBudget createAgentValueBudget() { return new ResultValueBudget(bytes); }
        };
    }

    private static ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest agentCommand(Connection connection,
            String sql, int pageSize) throws Exception {
        var command = new ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest();
        command.setScript(sql);
        command.setSingle(true);
        command.setPageNo(1);
        command.setPageSize(pageSize);
        command.setDatabaseName(connection.getCatalog());
        command.setSchemaName("PUBLIC");
        command.setFullResultValues(true);
        return command;
    }

    @Test
    void smallValuesRemainInlineEditableCells() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:sql_executor_small_cell;DB_CLOSE_DELAY=-1")) {
            putContext(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE doc (id INT PRIMARY KEY, content VARCHAR(32))");
                statement.execute("INSERT INTO doc (id, content) VALUES (1, 'small')");
            }

            ExecuteResponse result = DefaultSQLExecutor.getInstance().execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT content FROM doc")
                    .connection(connection)
                    .limitRowSize(true)
                    .offset(0)
                    .count(1)
                    .build());

            ResultCell cell = result.getDataList().get(0).get(0);
            assertEquals("small", cell.getValue());
            assertFalse(cell.isLargeValue());
            assertEquals(Types.VARCHAR, cell.getSqlType());
        }
    }

    @Test
    void legacyQueriesStillInvokeTheExistingResultReaderOverride() throws Exception {
        class LegacyExecutor extends DefaultSQLExecutor {
            @Override
            protected ExecuteResponse generateQueryExecuteResponse(java.sql.Statement statement, boolean limit,
                    Integer offset, Integer count) {
                return ExecuteResponse.builder().success(true)
                        .dataList(java.util.List.of(java.util.List.of(ResultCell.of("legacy reader")))).build();
            }
            java.util.List<ExecuteResponse> run(Connection connection) throws Exception {
                return executeMulti(new ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement("SELECT 1"),
                        connection, true, 0, 10, null);
            }
        }
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:default_legacy_reader")) {
            assertEquals("legacy reader", new LegacyExecutor().run(connection).get(0).getDataList().get(0).get(0).getValue());
        }
    }

    @Test
    void resultHeadersIncludeJdbcNamespaceProvenance() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:sql_executor_header_namespace;DB_CLOSE_DELAY=-1")) {
            putContext(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA tenant_b");
                statement.execute("CREATE TABLE tenant_b.users (email VARCHAR(128))");
                statement.execute("INSERT INTO tenant_b.users (email) VALUES ('alice@example.com')");
            }

            ExecuteResponse result = DefaultSQLExecutor.getInstance().execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT email FROM tenant_b.users")
                    .connection(connection)
                    .limitRowSize(true)
                    .offset(0)
                    .count(1)
                    .build());

            Header header = result.getHeaderList().get(0);
            assertEquals(connection.getCatalog(), header.getDatabaseName());
            assertEquals("TENANT_B", header.getSchemaName());
            assertEquals("USERS", header.getTableName());
            assertEquals("EMAIL", header.getColumnName());
        }
    }

    private static void putContext(Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(101L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("");
        connectInfo.setSchemaName("PUBLIC");
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    private static class H2MetaData extends DefaultMetaService implements IDbMetaData {
        @Override
        public String getMetaDataName(String... names) {
            return java.util.Arrays.stream(names)
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> "\"" + name.replace("\"", "") + "\"")
                    .reduce((first, second) -> first + "." + second)
                    .orElse("");
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final DBConfig dbConfig;
        private final IDbMetaData metaData = new H2MetaData();

        private TestPlugin() {
            dbConfig = new DBConfig();
            dbConfig.setDbType(TEST_DB_TYPE);
        }

        @Override
        public DBConfig getDBConfig() {
            return dbConfig;
        }

        @Override
        public IDbMetaData getDbMetaData() {
            return metaData;
        }
    }
}
