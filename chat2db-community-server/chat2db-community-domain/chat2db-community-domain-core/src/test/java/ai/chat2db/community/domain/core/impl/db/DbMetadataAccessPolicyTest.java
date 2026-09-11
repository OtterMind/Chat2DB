package ai.chat2db.community.domain.core.impl.db;

import static ai.chat2db.community.domain.core.cache.CacheKey.getTableKey;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.request.db.DbTablePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DbMetadataAccessPolicyTest {

    private static final String DB_TYPE = "METADATA_POLICY_TEST";
    private static final long DATA_SOURCE_ID = 920_001L;
    private static final String DATABASE = "app";
    private static final String SCHEMA = "public";

    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metadata = new DefaultMetaService() {
            @Override
            public List<TableColumn> columns(Connection connection, TableMetadataRequest request) {
                return List.of(column("id"), column("secret_value"));
            }
        };
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metadata;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDataSourceId(DATA_SOURCE_ID);
        connectInfo.setDriverConfig(config.getDefaultDriverConfig());
        connectInfo.setConnection(connection());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        MemoryCacheManage.remove(getTableKey(DATA_SOURCE_ID, DATABASE, SCHEMA));
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
    }

    @Test
    void tablePaginationFiltersBeforeComputingTotal() {
        MemoryCacheManage.put(getTableKey(DATA_SOURCE_ID, DATABASE, SCHEMA),
                new ArrayList<>(List.of(table("secret"), table("orders"), table("audit"))));
        DbTableServiceImpl service = service();
        DbTablePageQueryRequest request = DbTablePageQueryRequest.builder()
                .dataSourceId(DATA_SOURCE_ID).databaseName(DATABASE).schemaName(SCHEMA)
                .pageNo(1).pageSize(10).build();

        PageResponse<Table> page = service.pageQuery(request, null);

        assertEquals(1L, page.getTotal());
        assertEquals(List.of("orders"), page.getData().stream().map(Table::getName).toList());
    }

    @Test
    void columnListReturnsOnlyAuthorizedColumns() {
        DbTableQueryRequest request = DbTableQueryRequest.builder()
                .dataSourceId(DATA_SOURCE_ID).databaseName(DATABASE).schemaName(SCHEMA)
                .tableName("orders").refresh(true).build();

        List<TableColumn> columns = service().queryColumns(request);

        assertEquals(List.of("id"), columns.stream().map(TableColumn::getName).toList());
    }

    private DbTableServiceImpl service() {
        MetadataAccessPolicyManager manager = new MetadataAccessPolicyManager(List.of(resources -> resources.stream()
                .map(resource -> resource.getColumnName() == null
                        ? "orders".equals(resource.getTableName())
                        : "id".equals(resource.getColumnName()))
                .toList()));
        return new DbTableServiceImpl(null, manager);
    }

    private Table table(String name) {
        return Table.builder().name(name).databaseName(DATABASE).schemaName(SCHEMA).build();
    }

    private TableColumn column(String name) {
        return TableColumn.builder().name(name).tableName("orders").databaseName(DATABASE)
                .schemaName(SCHEMA).build();
    }

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "MetadataPolicyTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }
}
