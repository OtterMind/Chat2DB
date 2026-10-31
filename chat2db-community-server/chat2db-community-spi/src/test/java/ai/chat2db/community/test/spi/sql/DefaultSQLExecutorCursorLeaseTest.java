package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultCursorHold;
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
import ai.chat2db.spi.sql.ResultCursorLeaseRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cursor a query leaves for a caller that attaches the large values itself, which is the path every REST
 * query takes: a value the driver handed over as a handle is only read after the query, so the cursor of that query
 * stays open until the connection context it ran in goes away, and a reader that leases the value keeps it alive
 * beyond that.
 */
class DefaultSQLExecutorCursorLeaseTest {

    private static final String TEST_DB_TYPE = "H2";
    private static final DefaultSQLExecutor EXECUTOR = new DefaultSQLExecutor();
    private static final int LARGE_CLOB_CHARS = 2 * 1024 * 1024;

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
        ResultCursorLeaseRegistry.releaseAll();
        ResultCursorLeaseRegistry.end();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void aValueTheDriverHandedOverKeepsItsCursorForTheReader() throws Exception {
        try (Connection connection = openDatabase("cursor_lease")) {
            RecordingConnection recording = new RecordingConnection(connection);
            Connection recorded = recording.proxy();
            putContext(recorded);

            ExecuteResponse select = EXECUTOR.execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT payload_clob FROM lease_sample WHERE id = 1")
                    .connection(recorded)
                    .limitRowSize(true)
                    .build());

            ResultCell cell = select.getDataList().get(0).get(0);
            assertTrue(cell.isLargeValue(), "a 2MiB text value is reported as a large value");
            assertTrue(cell.carriesHandle(), "the driver hands the value over instead of transferring it");

            // The reader leases the value while the response is still handled, like the large value layer does
            ResultCursorHold hold = ResultCursorLeaseRegistry.holdForCurrentExecution();
            assertNotNull(hold, "the query leaves a cursor behind for the reader to lease");

            Statement statement = recording.lastStatement();
            // The connection context of the query goes away, like at the end of the request that ran it
            Chat2DBContext.removeContext();
            assertFalse(statement.isClosed(), "a leased cursor is not closed with its connection context");

            hold.close();
            assertTrue(statement.isClosed(), "the cursor is closed when the reader releases the value");
        }
    }

    @Test
    void aValueTheDriverHandedOverThatNobodyLeasesIsGivenUpWithItsConnectionContext() throws Exception {
        try (Connection connection = openDatabase("cursor_lease_unread")) {
            RecordingConnection recording = new RecordingConnection(connection);
            Connection recorded = recording.proxy();
            putContext(recorded);

            EXECUTOR.execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT payload_clob FROM lease_sample WHERE id = 1")
                    .connection(recorded)
                    .limitRowSize(true)
                    .build());

            Statement statement = recording.lastStatement();
            assertFalse(statement.isClosed(), "the cursor waits for a reader that may still lease the value");

            Chat2DBContext.removeContext();

            assertTrue(statement.isClosed(), "a cursor nobody leased is given up with its connection context");
        }
    }

    @Test
    void aQueryWithoutAValueTheDriverHandsOverClosesItsOwnCursor() throws Exception {
        try (Connection connection = openDatabase("cursor_lease_small")) {
            RecordingConnection recording = new RecordingConnection(connection);
            Connection recorded = recording.proxy();
            putContext(recorded);

            ExecuteResponse select = EXECUTOR.execute(SqlStatementExecuteRequest.builder()
                    .sql("SELECT payload_text FROM lease_sample WHERE id = 1")
                    .connection(recorded)
                    .limitRowSize(true)
                    .build());

            assertFalse(select.getDataList().get(0).get(0).carriesHandle(),
                    "a value the driver transfers is not a handle");
            assertTrue(recording.lastStatement().isClosed(), "nothing can lease from it, so the query closes it");
            assertNull(ResultCursorLeaseRegistry.holdForCurrentExecution(),
                    "and it leaves no cursor behind for a reader");
        }
    }

    private static Connection openDatabase(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE lease_sample (id INT PRIMARY KEY, payload_text VARCHAR(32),"
                    + " payload_clob CLOB)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO lease_sample (id, payload_text, payload_clob) VALUES (1, 'small', ?)")) {
            insert.setCharacterStream(1, new StringReader("租".repeat(LARGE_CLOB_CHARS)),
                    LARGE_CLOB_CHARS);
            insert.execute();
        }
        return connection;
    }

    private static void putContext(Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
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
     * A connection that hands out the statements it creates, so a test can see when the cursor of a query is closed.
     */
    private static final class RecordingConnection implements InvocationHandler {

        private final Connection delegate;
        private final List<Statement> statements = new ArrayList<>();

        private RecordingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        private Connection proxy() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        private Statement lastStatement() {
            assertFalse(statements.isEmpty(), "the query must have prepared a statement");
            return statements.get(statements.size() - 1);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if ("prepareStatement".equals(method.getName()) && result instanceof Statement statement) {
                statements.add(statement);
            }
            return result;
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final DBConfig dbConfig;
        private final IDbMetaData metaData = new DefaultMetaService();
        private final IDbManager dbManager = new DefaultDBManager();

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
