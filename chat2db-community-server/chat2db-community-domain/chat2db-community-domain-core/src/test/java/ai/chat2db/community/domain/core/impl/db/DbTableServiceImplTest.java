package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.TableSelector;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void queryLoadsImportedForeignKeysForTableEditorReload() {
        TestMetaData metaData = new TestMetaData();
        DBConfig dbConfig = new DBConfig();
        dbConfig.setDbType(TEST_DB_TYPE);
        dbConfig.setDefaultDriverConfig(new DriverConfig());
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin(dbConfig, metaData));
        Chat2DBContext.putContext(connectInfo());

        DbTableQueryRequest request = new DbTableQueryRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName("app");
        request.setTableName("child");
        request.setRefresh(true);

        Table table = tableService.query(request, TableSelector.builder().columnList(true).indexList(true).build());

        assertEquals(1, table.getForeignKeyList().size());
        assertEquals("fk_child_parent", table.getForeignKeyList().get(0).getFkName());
        assertEquals("parent", table.getForeignKeyList().get(0).getPkTableName());
        assertEquals((short) 1, table.getForeignKeyList().get(0).getKeySeq());
        assertEquals("app", metaData.importedKeysRequest.getDatabaseName());
        assertEquals("child", metaData.importedKeysRequest.getTableName());
    }

    private static ConnectInfo connectInfo() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(7L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setDatabaseName("app");
        connectInfo.setConnection(fakeConnection());
        return connectInfo;
    }

    private static Connection fakeConnection() {
        return (Connection) Proxy.newProxyInstance(DbTableServiceImplTest.class.getClassLoader(),
                new Class[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "toString" -> "fake-connection";
                    default -> null;
                });
    }

    private static final class TestPlugin implements IPlugin {
        private final DBConfig dbConfig;
        private final TestMetaData metaData;

        private TestPlugin(DBConfig dbConfig, TestMetaData metaData) {
            this.dbConfig = dbConfig;
            this.metaData = metaData;
        }

        @Override
        public DBConfig getDBConfig() {
            return dbConfig;
        }

        @Override
        public TestMetaData getDbMetaData() {
            return metaData;
        }
    }

    private static final class TestMetaData extends DefaultMetaService {
        private TableMetadataRequest importedKeysRequest;

        @Override
        public List<Table> tables(Connection connection, TablesRequest tablesRequest) {
            Table table = new Table();
            table.setName("child");
            table.setDatabaseName("app");
            return List.of(table);
        }

        @Override
        public List<TableColumn> columns(Connection connection, TableMetadataRequest tableMetadataRequest) {
            TableColumn column = new TableColumn();
            column.setName("parent_id");
            return List.of(column);
        }

        @Override
        public List<TableIndex> indexes(Connection connection, TableMetadataRequest tableMetadataRequest) {
            return List.of();
        }

        @Override
        public List<ForeignKeyInfo> getImportedKeys(Connection connection, TableMetadataRequest tableMetadataRequest) {
            importedKeysRequest = tableMetadataRequest;
            ForeignKeyInfo foreignKey = new ForeignKeyInfo();
            foreignKey.setFkName("fk_child_parent");
            foreignKey.setFkTableName("child");
            foreignKey.setFkColumnName("parent_id");
            foreignKey.setPkTableName("parent");
            foreignKey.setPkColumnName("id");
            foreignKey.setKeySeq((short) 1);
            foreignKey.setUpdateRule((short) 1);
            foreignKey.setDeleteRule((short) 0);
            return List.of(foreignKey);
        }
    }
}
