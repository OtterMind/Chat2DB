package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbStreamingExecuteRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionCancellation;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.api.service.db.extension.ISqlExecutionPolicy;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbSqlExecutionServicePolicyTest {

    private static final String TEST_DB_TYPE = "STREAMING_SQL_POLICY_TEST";

    private final AtomicReference<SqlExecuteRequest> executedRequest = new AtomicReference<>();
    private final AtomicInteger executorCalls = new AtomicInteger();
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(7L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("shop");
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void streamingExecutionAppliesPolicyBeforeJdbcAndFiltersEveryResultEvent() throws SQLException {
        List<String> events = new ArrayList<>();
        AtomicReference<SqlExecutionPlan> authorizedPlan = new AtomicReference<>();
        ISqlExecutionPolicy policy = new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                return sql + " WHERE tenant_id = 7";
            }

            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 2;
            }

            @Override
            public void beforeExecute(SqlExecutionPlan plan) {
                events.add("authorized");
                authorizedPlan.set(plan);
            }

            @Override
            public boolean includeColumn(SqlResultColumnContext context) {
                return !"secret".equals(context.getColumnName());
            }
        };
        RecordingConsumer consumer = new RecordingConsumer();
        DbSqlExecutionServiceImpl service = service(policy, events);

        service.executeStreaming(request("sql-job-42", consumer, () -> false));

        assertEquals(List.of("authorized", "jdbc"), events);
        assertEquals("sql-job-42", authorizedPlan.get().getExecutionId());
        assertEquals(99L, authorizedPlan.get().getContext().getApplyId());
        assertEquals("SELECT id, secret FROM orders WHERE tenant_id = 7", executedRequest.get().getScript());
        assertEquals(2, executedRequest.get().getPageSize());
        assertFalse(executedRequest.get().getPageSizeAll());
        assertEquals(List.of("#", "id"), consumer.startedHeaders.stream().map(Header::getName).toList());
        assertEquals(List.of(List.of("1", "7"), List.of("2", "8")), display(consumer.rows));
        assertEquals(List.of(List.of("1", "7"), List.of("2", "8")),
                consumer.finished.getDisplayDataList());
        assertFalse(consumer.finished.isCanEdit());
        assertFalse(consumer.finished.getHasNextPage());
        assertEquals("2", consumer.finished.getFuzzyTotal());
    }

    @Test
    void canceledExecutionDoesNotConsumeAuthorization() {
        AtomicInteger authorizationCalls = new AtomicInteger();
        ISqlExecutionPolicy policy = new ISqlExecutionPolicy() {
            @Override
            public void beforeExecute(SqlExecutionPlan plan) {
                authorizationCalls.incrementAndGet();
            }
        };
        DbSqlExecutionServiceImpl service = service(policy, new ArrayList<>());

        assertThrows(SQLException.class,
                () -> service.executeStreaming(request("sql-job-canceled", new RecordingConsumer(), () -> true)));
        assertEquals(0, authorizationCalls.get());
        assertEquals(0, executorCalls.get());
    }

    private DbSqlExecutionServiceImpl service(ISqlExecutionPolicy policy, List<String> events) {
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin(streamingExecutor(events)));
        return new DbSqlExecutionServiceImpl(this::toCommand,
                new SqlExecutionPolicyManager(List.of(policy)));
    }

    private DbStreamingExecuteRequest request(String executionId, ISqlExecutionResultConsumer consumer,
            ISqlExecutionCancellation cancellation) {
        DbDlExecuteRequest request = new DbDlExecuteRequest();
        request.setSql("SELECT id, secret FROM orders");
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");
        request.setTableName("orders");
        request.setPageSize(100);
        request.setPageSizeAll(true);
        request.setApplyId(99L);

        DbStreamingExecuteRequest streamingRequest = new DbStreamingExecuteRequest();
        streamingRequest.setExecutionId(executionId);
        streamingRequest.setDlExecuteRequest(request);
        streamingRequest.setConsumer(consumer);
        streamingRequest.setStatementListener(new ISqlExecutionStatementListener() {
            @Override
            public void onStatementCreated(Statement statement) {
            }

            @Override
            public void onStatementClosed(Statement statement) {
            }
        });
        streamingRequest.setCancellation(cancellation);
        return streamingRequest;
    }

    private SqlExecuteRequest toCommand(DbDlExecuteRequest request) {
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

    private IPlugin plugin() {
        return plugin(streamingExecutor(new ArrayList<>()));
    }

    private IPlugin plugin(DefaultSQLExecutor executor) {
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

    private DefaultSQLExecutor streamingExecutor(List<String> events) {
        return new DefaultSQLExecutor() {
            @Override
            public void executeStreaming(SqlExecuteRequest command, ISqlExecutionResultConsumer consumer,
                    ISqlExecutionStatementListener statementListener, ISqlExecutionCancellation cancellation) {
                executorCalls.incrementAndGet();
                executedRequest.set(command);
                events.add("jdbc");
                Header rowNumber = Header.builder().name("#")
                        .dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode()).build();
                Header id = Header.builder().name("id").columnName("id").build();
                Header secret = Header.builder().name("secret").columnName("secret").build();
                List<List<ResultCell>> allRows = new ArrayList<>(List.of(
                        row("1", "7", "hidden-1"),
                        row("2", "8", "hidden-2"),
                        row("3", "9", "hidden-3")));
                ExecuteResponse result = new ExecuteResponse();
                result.setSuccess(true);
                result.setCanEdit(true);
                result.setHeaderList(new ArrayList<>(List.of(rowNumber, id, secret)));
                result.setDataList(allRows);
                result.setHasNextPage(true);
                result.setFuzzyTotal("3+");
                consumer.statementStarted(command.getScript(), command.getScript(), null);
                consumer.resultStarted(result);
                consumer.rows(result, allRows);
                consumer.resultFinished(result);
                consumer.statementFinished(command.getScript(), 1L);
            }
        };
    }

    private static List<ResultCell> row(String... values) {
        List<ResultCell> row = new ArrayList<>(values.length);
        for (String value : values) {
            row.add(ResultCell.of(value));
        }
        return row;
    }

    private static List<List<String>> display(List<List<ResultCell>> rows) {
        return rows.stream().map(row -> row.stream().map(ResultCell::getValue).toList()).toList();
    }

    private static final class RecordingConsumer implements ISqlExecutionResultConsumer {

        private List<Header> startedHeaders;
        private final List<List<ResultCell>> rows = new ArrayList<>();
        private ExecuteResponse finished;

        @Override
        public void statementStarted(String sql, String originalSql, String comment) {
        }

        @Override
        public void resultStarted(ExecuteResponse result) {
            startedHeaders = new ArrayList<>(result.getHeaderList());
        }

        @Override
        public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
            this.rows.addAll(rows);
        }

        @Override
        public void resultFinished(ExecuteResponse result) {
            finished = result;
        }

        @Override
        public void updateCount(ExecuteResponse result) {
        }

        @Override
        public void statementFinished(String sql, long duration) {
        }
    }
}
