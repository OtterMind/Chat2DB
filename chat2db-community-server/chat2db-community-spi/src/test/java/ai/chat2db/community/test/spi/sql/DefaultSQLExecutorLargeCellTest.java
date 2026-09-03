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
import java.util.List;
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

    @Test
    void queryResultStripsLegacyTypoAutoRowIdColumn() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:sql_executor_legacy_auto_row_id;DB_CLOSE_DELAY=-1")) {
            putContext(connection);

            ExecuteResponse result = DefaultSQLExecutor.getInstance().execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT 7 AS CAHT2DB_AUTO_ROW_ID, 'visible' AS name")
                    .connection(connection)
                    .limitRowSize(true)
                    .offset(0)
                    .count(1)
                    .build());

            assertEquals(List.of("NAME"), result.getHeaderList().stream().map(Header::getName).toList());
            assertEquals(List.of(List.of("visible")), result.getDisplayDataList());
        }
    }

    @Test
    void queryResultStripsCorrectAutoRowIdColumn() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:sql_executor_correct_auto_row_id;DB_CLOSE_DELAY=-1")) {
            putContext(connection);

            ExecuteResponse result = DefaultSQLExecutor.getInstance().execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT 7 AS CHAT2DB_AUTO_ROW_ID, 'visible' AS name")
                    .connection(connection)
                    .limitRowSize(true)
                    .offset(0)
                    .count(1)
                    .build());

            assertEquals(List.of("NAME"), result.getHeaderList().stream().map(Header::getName).toList());
            assertEquals(List.of(List.of("visible")), result.getDisplayDataList());
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
