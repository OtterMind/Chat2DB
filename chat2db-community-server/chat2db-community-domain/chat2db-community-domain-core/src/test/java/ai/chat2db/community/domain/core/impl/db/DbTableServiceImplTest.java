package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbTableServiceImplTest {

    private static final String TEST_DB_TYPE = "ISSUE_1830_TEST";
    private static final String CREATE_EXAMPLE = "CREATE TABLE issue_1830 (id BIGINT)";
    private static final String ALTER_EXAMPLE = "ALTER TABLE issue_1830 ADD name VARCHAR(64)";

    private IPlugin previousPlugin;
    private DbTableServiceImpl tableService;

    @BeforeEach
    void setUp() {
        DBConfig dbConfig = new DBConfig();
        dbConfig.setDbType(TEST_DB_TYPE);
        dbConfig.setSimpleCreateTable(CREATE_EXAMPLE);
        dbConfig.setSimpleAlterTable(ALTER_EXAMPLE);

        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, () -> dbConfig);
        Chat2DBContext.removeContext();
        tableService = new DbTableServiceImpl(null);
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
    void createTableExampleUsesRequestedDatabaseTypeWithoutConnectionContext() {
        assertEquals(CREATE_EXAMPLE, tableService.createTableExample(TEST_DB_TYPE));
    }

    @Test
    void alterTableExampleUsesRequestedDatabaseTypeWithoutConnectionContext() {
        assertEquals(ALTER_EXAMPLE, tableService.alterTableExample(TEST_DB_TYPE));
    }

    @Test
    void repairMaintenanceSqlRejectsUnsupportedMysqlStorageEngineBeforeSqlGeneration() {
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, pluginWithTableEngine("InnoDB"));
        Chat2DBContext.putContext(connectInfo());

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> tableService.maintenanceSql(tableRequest(), "REPAIR"));

        assertEquals("mysql.maintenance.engineUnsupported", thrown.getMessage());
    }

    private static DbTableQueryRequest tableRequest() {
        return DbTableQueryRequest.builder()
                .dataSourceId(7L)
                .databaseName("shop")
                .tableName("orders")
                .build();
    }

    private static ConnectInfo connectInfo() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(7L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection());
        return connectInfo;
    }

    private static Connection connection() {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(DbTableServiceImplTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "close" -> null;
                    default -> null;
                });
    }

    private static IPlugin pluginWithTableEngine(String engine) {
        DBConfig dbConfig = new DBConfig();
        dbConfig.setDbType(TEST_DB_TYPE);
        IDbMetaData metaData = new DefaultMetaService() {
            @Override
            public List<Table> tables(Connection connection, TablesRequest tablesRequest) {
                return List.of(Table.builder()
                        .name(tablesRequest.getTableName())
                        .engine(engine)
                        .build());
            }
        };
        IDbManager dbManager = new DefaultDBManager() {
            @Override
            public Connection getConnection(ConnectInfo connectInfo) {
                return connectInfo.getConnection();
            }

            @Override
            public String repairTable(Connection connection, String databaseName, String schemaName, String tableName)
                    throws SQLException {
                return "REPAIR TABLE `" + databaseName + "`.`" + tableName + "`";
            }
        };
        return new IPlugin() {
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
        };
    }
}
