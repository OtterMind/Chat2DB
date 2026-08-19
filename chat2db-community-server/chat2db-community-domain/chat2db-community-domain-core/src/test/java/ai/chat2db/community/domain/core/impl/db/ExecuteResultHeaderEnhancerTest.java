package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.plugin.ResultSetEditorTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.PrimaryKey;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.request.db.DbExecuteResultEnhanceRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultSetEditorMetadata;
import ai.chat2db.community.domain.api.model.result.ResultSetEditorOption;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteResultHeaderEnhancerTest {

    private static final String TEST_DB_TYPE = "RESULT_HEADER_ENHANCER_TEST";

    private Connection connection;
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:result_header_enhancer;DB_CLOSE_DELAY=-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void resolvesAliasedHeaderMetadataFromMatchedColumn() {
        AtomicInteger columnQueries = new AtomicInteger();
        AtomicReference<DbTableQueryRequest> capturedRequest = new AtomicReference<>();
        TableColumn statusColumn = TableColumn.builder()
                .name("status")
                .columnType("ENUM")
                .comment("workflow status")
                .build();
        IDbTableService tableService = tableService(List.of(statusColumn), columnQueries, capturedRequest);
        TestMetaData metaData = new TestMetaData(column -> {
            assertSame(statusColumn, column);
            return ResultSetEditorMetadata.builder()
                    .editorType(ResultSetEditorTypeEnum.SELECT.getCode())
                    .editorOptions(List.of(
                            ResultSetEditorOption.builder().label("PENDING").value("PENDING").build(),
                            ResultSetEditorOption.builder().label("DONE").value("DONE").build()))
                    .build();
        });
        putContext(metaData);

        Header header = Header.builder()
                .name("status_alias")
                .columnName("status")
                .editorType(ResultSetEditorTypeEnum.TEXT.getCode())
                .build();
        ExecuteResponse response = editableResponse(header);
        enhance(tableService, response);

        assertEquals(1, columnQueries.get());
        assertEquals(1, metaData.getResolverCalls());
        assertTrue(capturedRequest.get().isRefresh());
        assertEquals("orders", capturedRequest.get().getTableName());
        assertEquals("workflow status", header.getComment());
        assertEquals(ResultSetEditorTypeEnum.SELECT.getCode(), header.getEditorType());
        assertEquals(List.of("PENDING", "DONE"),
                header.getEditorOptions().stream().map(ResultSetEditorOption::getValue).toList());
    }

    @Test
    void resolverFailureKeepsExistingEditorAndDoesNotStopOtherColumns() {
        AtomicInteger columnQueries = new AtomicInteger();
        TableColumn brokenColumn = TableColumn.builder().name("broken").columnType("ENUM").build();
        TableColumn createdAtColumn = TableColumn.builder().name("created_at").columnType("DATETIME").build();
        IDbTableService tableService = tableService(List.of(brokenColumn, createdAtColumn), columnQueries,
                new AtomicReference<>());
        TestMetaData metaData = new TestMetaData(column -> {
            if (column == brokenColumn) {
                throw new IllegalArgumentException("malformed metadata");
            }
            return ResultSetEditorMetadata.builder()
                    .editorType(ResultSetEditorTypeEnum.DATETIME.getCode())
                    .editorOptions(List.of())
                    .build();
        });
        putContext(metaData);

        Header brokenHeader = Header.builder()
                .name("broken")
                .columnName("broken")
                .editorType(ResultSetEditorTypeEnum.TEXT.getCode())
                .build();
        Header createdAtHeader = Header.builder()
                .name("created_at")
                .columnName("created_at")
                .editorType(ResultSetEditorTypeEnum.TEXT.getCode())
                .build();
        ExecuteResponse response = editableResponse(brokenHeader, createdAtHeader);
        enhance(tableService, response);

        assertEquals(1, columnQueries.get());
        assertEquals(2, metaData.getResolverCalls());
        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(), brokenHeader.getEditorType());
        assertNull(brokenHeader.getEditorOptions());
        assertEquals(ResultSetEditorTypeEnum.DATETIME.getCode(), createdAtHeader.getEditorType());
        assertEquals(List.of(), createdAtHeader.getEditorOptions());
    }

    @Test
    void defaultMetadataKeepsLegacyEditorTypeWithEmptyOptions() {
        TableColumn column = TableColumn.builder().name("created_at").columnType("DATETIME").build();
        IDbTableService tableService = tableService(List.of(column), new AtomicInteger(), new AtomicReference<>());
        IDbMetaData metaData = new DefaultMetaService() {
            @Override
            public List<PrimaryKey> getPrimaryKeys(Connection connection,
                                                   TableMetadataRequest tableMetadataRequest) {
                return List.of();
            }

            @Override
            public String resolveResultSetEditorType(String typeName, Integer type) {
                return ResultSetEditorTypeEnum.DATETIME.getCode();
            }
        };
        putContext(metaData);

        Header header = Header.builder()
                .name("created_at")
                .columnName("created_at")
                .editorType(ResultSetEditorTypeEnum.TEXT.getCode())
                .build();
        enhance(tableService, editableResponse(header));

        assertEquals(ResultSetEditorTypeEnum.DATETIME.getCode(), header.getEditorType());
        assertEquals(List.of(), header.getEditorOptions());
    }

    private void putContext(IDbMetaData metaData) {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin(metaData));
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(17L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("catalog");
        connectInfo.setSchemaName("schema");
        connectInfo.setConnection(connection);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    private IDbTableService tableService(List<TableColumn> columns, AtomicInteger queryCount,
                                         AtomicReference<DbTableQueryRequest> capturedRequest) {
        return (IDbTableService) Proxy.newProxyInstance(
                IDbTableService.class.getClassLoader(),
                new Class<?>[]{IDbTableService.class},
                (proxy, method, args) -> {
                    if ("queryColumns".equals(method.getName())) {
                        queryCount.incrementAndGet();
                        capturedRequest.set((DbTableQueryRequest) args[0]);
                        return columns;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private ExecuteResponse editableResponse(Header... headers) {
        return ExecuteResponse.builder()
                .success(true)
                .canEdit(true)
                .tableName("orders")
                .headerList(List.of(headers))
                .build();
    }

    private void enhance(IDbTableService tableService, ExecuteResponse response) {
        DbExecuteResultEnhanceRequest request = new DbExecuteResultEnhanceRequest();
        request.setExecuteResult(response);
        new ExecuteResultHeaderEnhancer(tableService).enhance(request);
    }

    private static final class TestMetaData extends DefaultMetaService {

        private final Function<TableColumn, ResultSetEditorMetadata> resolver;
        private final AtomicInteger resolverCalls = new AtomicInteger();

        private TestMetaData(Function<TableColumn, ResultSetEditorMetadata> resolver) {
            this.resolver = resolver;
        }

        @Override
        public List<PrimaryKey> getPrimaryKeys(Connection connection, TableMetadataRequest tableMetadataRequest) {
            return List.of();
        }

        @Override
        public ResultSetEditorMetadata resolveResultSetEditorMetadata(TableColumn column) {
            resolverCalls.incrementAndGet();
            return resolver.apply(column);
        }

        private int getResolverCalls() {
            return resolverCalls.get();
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final IDbMetaData metaData;

        private TestPlugin(IDbMetaData metaData) {
            this.metaData = metaData;
        }

        @Override
        public DBConfig getDBConfig() {
            DBConfig dbConfig = new DBConfig();
            dbConfig.setDbType(TEST_DB_TYPE);
            return dbConfig;
        }

        @Override
        public IDbMetaData getDbMetaData() {
            return metaData;
        }
    }
}
