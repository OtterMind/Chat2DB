package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.request.db.DbCopyInValuesRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlCountRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSelectResultUpdateRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlValidateRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext;
import ai.chat2db.community.domain.api.service.db.extension.ISqlExecutionPolicy;
import ai.chat2db.community.domain.core.converter.CommandConverter;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SqlStatementExecuteRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDlTemplateServicePolicyTest {

    private static final String TEST_DB_TYPE = "SQL_POLICY_TEST";

    private final AtomicReference<SqlExecuteRequest> capturedRequest = new AtomicReference<>();
    private final AtomicReference<String> capturedDirectSql = new AtomicReference<>();
    private final AtomicReference<String> capturedUpdateSql = new AtomicReference<>();
    private final AtomicReference<SqlStatementExecuteRequest> capturedValidationRequest = new AtomicReference<>();
    private final AtomicInteger tableExecutorCalls = new AtomicInteger();
    private Connection connection;
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() throws SQLException {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection("jdbc:h2:mem:sql-policy-test;DB_CLOSE_DELAY=-1");
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(7L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("shop");
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Chat2DBContext.removeContext();
        connection.close();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void executeAppliesSqlRewriteRowLimitAndColumnFilterBeforeReturning() {
        AtomicReference<SqlExecutionContext> capturedContext = new AtomicReference<>();
        ISqlExecutionPolicy policy = new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                capturedContext.set(context);
                return sql + " where tenant_id = 7";
            }

            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 2;
            }

            @Override
            public boolean includeColumn(SqlResultColumnContext context) {
                return !"secret".equals(context.getColumnName());
            }
        };
        DbDlTemplateServiceImpl service = new DbDlTemplateServiceImpl(
                new ExecuteResultHeaderEnhancer(null), converter(),
                new SqlExecutionPolicyManager(List.of(policy)));
        DbDlExecuteRequest request = new DbDlExecuteRequest();
        request.setSql("select id, secret from orders");
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");
        request.setTableName("orders");
        request.setPageSize(100);
        request.setApplyId(99L);

        List<ExecuteResponse> results = service.execute(request);

        SqlExecuteRequest executed = capturedRequest.get();
        assertEquals("select id, secret from orders where tenant_id = 7", executed.getScript());
        assertEquals(99L, capturedContext.get().getApplyId());
        assertEquals(2, executed.getPageSize());
        assertEquals(List.of("id"), results.get(0).getHeaderList().stream().map(Header::getName).toList());
        assertEquals(List.of("7"), results.get(0).getDisplayDataList().get(0));
    }

    @Test
    void tableBrowseUsesTheRewrittenSqlInsteadOfThePolicyBypassingTableExecutor() {
        DbDlTemplateServiceImpl service = service(new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                return sql + " WHERE tenant_id = 7";
            }

            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 2;
            }
        });
        DbDlExecuteRequest request = request(null);
        request.setPageSize(100);

        service.executeSelectTable(request);

        assertEquals(0, tableExecutorCalls.get());
        assertEquals("SELECT * FROM shop.orders WHERE tenant_id = 7", capturedRequest.get().getScript());
        assertEquals(2, capturedRequest.get().getPageSize());
    }

    @Test
    void countValidatesAndCapsThePolicyRewrittenQuery() {
        DbDlTemplateServiceImpl service = service(new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                return sql + " WHERE tenant_id = 7";
            }

            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 2;
            }
        });
        DbDlCountRequest request = new DbDlCountRequest();
        request.setSql("SELECT * FROM orders");
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");
        request.setTableName("orders");

        long count = service.count(request);

        assertEquals(2L, count);
        assertTrue(capturedDirectSql.get().contains("tenant_id = 7"));
    }

    @Test
    void validateAndTableEditCannotBypassSqlAuthorization() {
        DbDlTemplateServiceImpl service = service(new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                return sql.startsWith("SELECT") ? "SELECT 2" : sql + " WHERE tenant_id = 7";
            }
        });
        DbSqlValidateRequest validateRequest = new DbSqlValidateRequest();
        validateRequest.setSql("SELECT 1");
        validateRequest.setDataSourceId(7L);
        validateRequest.setDatabaseName("shop");

        service.validate(validateRequest);
        service.executeUpdate(request("UPDATE orders SET amount = 1"));

        assertEquals("SELECT 2", capturedDirectSql.get());
        assertEquals("UPDATE orders SET amount = 1 WHERE tenant_id = 7", capturedUpdateSql.get());
    }

    @Test
    void validateReauthorizesAndAppliesRowAndColumnLimits() {
        AtomicInteger beforeExecuteCalls = new AtomicInteger();
        DbDlTemplateServiceImpl service = service(new ISqlExecutionPolicy() {
            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 2;
            }

            @Override
            public void beforeExecute(SqlExecutionPlan plan) {
                beforeExecuteCalls.incrementAndGet();
            }

            @Override
            public boolean includeColumn(SqlResultColumnContext context) {
                return !"secret".equals(context.getColumnName());
            }
        });
        DbSqlValidateRequest request = new DbSqlValidateRequest();
        request.setSql("SELECT id, secret FROM orders");
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");

        ExecuteResponse result = service.validate(request);

        assertEquals(1, beforeExecuteCalls.get());
        assertEquals(2, capturedValidationRequest.get().getCount());
        assertEquals(2, result.getDataList().size());
        assertEquals(List.of("id"), result.getHeaderList().stream().map(Header::getName).toList());
    }

    private DbDlTemplateServiceImpl service(ISqlExecutionPolicy policy) {
        return new DbDlTemplateServiceImpl(new ExecuteResultHeaderEnhancer(null), converter(),
                new SqlExecutionPolicyManager(List.of(policy)));
    }

    private DbDlExecuteRequest request(String sql) {
        DbDlExecuteRequest request = new DbDlExecuteRequest();
        request.setSql(sql);
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");
        request.setTableName("orders");
        return request;
    }

    private IPlugin plugin() {
        DefaultSQLExecutor executor = new DefaultSQLExecutor() {
            @Override
            public List<ExecuteResponse> execute(SqlExecuteRequest request) {
                capturedRequest.set(request);
                ExecuteResponse response = new ExecuteResponse();
                response.setSuccess(true);
                response.setCanEdit(false);
                response.setHeaderList(new ArrayList<>(List.of(
                        Header.builder().name("id").columnName("id").build(),
                        Header.builder().name("secret").columnName("secret").build())));
                response.setDataList(new ArrayList<>(List.of(new ArrayList<>(List.of(
                        ResultCell.of("7"), ResultCell.of("hidden"))))));
                return List.of(response);
            }

            @Override
            public List<ExecuteResponse> executeSelectTable(SqlExecuteRequest request) {
                tableExecutorCalls.incrementAndGet();
                return execute(request);
            }

            @Override
            public Long count(String sql, Connection connection) {
                capturedDirectSql.set(sql);
                return 9L;
            }

            @Override
            public ExecuteResponse execute(SqlStatementExecuteRequest request) {
                capturedDirectSql.set(request.getSql());
                ExecuteResponse response = new ExecuteResponse();
                response.setSuccess(true);
                if (request.getSql().toUpperCase(Locale.ROOT).contains("COUNT(")) {
                    response.setDataList(List.of(List.of(ResultCell.of("9"))));
                    return response;
                }
                capturedValidationRequest.set(request);
                response.setHeaderList(new ArrayList<>(List.of(
                        Header.builder().name("id").columnName("id").build(),
                        Header.builder().name("secret").columnName("secret").build())));
                response.setDataList(new ArrayList<>(List.of(
                        new ArrayList<>(List.of(ResultCell.of("1"), ResultCell.of("hidden-1"))),
                        new ArrayList<>(List.of(ResultCell.of("2"), ResultCell.of("hidden-2"))),
                        new ArrayList<>(List.of(ResultCell.of("3"), ResultCell.of("hidden-3"))))));
                return response;
            }

            @Override
            public ExecuteResponse executeUpdate(String sql, Connection connection, int n) throws SQLException {
                capturedUpdateSql.set(sql);
                return ExecuteResponse.builder().sql(sql).success(true).build();
            }
        };
        IDbMetaData metaData = new DefaultMetaService() {
            @Override
            public DefaultSQLExecutor getCommandExecutor() {
                return executor;
            }
        };
        DBConfig config = new DBConfig();
        config.setDbType(TEST_DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        return new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metaData;
            }
        };
    }

    private CommandConverter converter() {
        return new CommandConverter() {
            @Override
            public SqlExecuteRequest param2model(DbDlExecuteRequest request) {
                SqlExecuteRequest command = new SqlExecuteRequest();
                command.setScript(request.getSql());
                command.setDataSourceId(request.getDataSourceId());
                command.setDatabaseName(request.getDatabaseName());
                command.setSchemaName(request.getSchemaName());
                command.setTableName(request.getTableName());
                command.setPageNo(request.getPageNo());
                command.setPageSize(request.getPageSize());
                command.setPageSizeAll(request.getPageSizeAll());
                return command;
            }

            @Override
            public QueryResponse updateSelectResult2query(DbSelectResultUpdateRequest request) {
                return null;
            }

            @Override
            public QueryResponse copyInValues2query(DbCopyInValuesRequest request) {
                return null;
            }
        };
    }
}
