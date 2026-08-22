package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.db.DatabaseObjectDeletePrepare;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseDeletePrepareRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseObjectDeleteExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaDeletePrepareRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.McpConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbObjectsQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseObjectDeleteServiceImplTest {

    private Map<String, IPlugin> originalPlugins;

    @BeforeEach
    void setUp() {
        originalPlugins = Map.copyOf(Chat2DBContext.PLUGIN_MAP);
        Chat2DBContext.PLUGIN_MAP.clear();
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.clear();
        Chat2DBContext.PLUGIN_MAP.putAll(originalPlugins);
    }

    @Test
    void preparesMysqlDatabaseDeleteWithUserReadableConfirmationName() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("app_db")), List.of());
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");

        DatabaseObjectDeletePrepare result = service.prepareDatabaseDelete(databasePrepare("app_db"));

        assertEquals("app_db", result.getConfirmName());
        assertEquals("DROP DATABASE `app_db`", result.getSqlPreview());
        assertTrue(dbManager.droppedDatabases.isEmpty());
    }

    @Test
    void refusesMysqlSchemaDeleteInsteadOfMappingItToDatabaseDelete() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("app_db")), List.of(schema("app_schema")));
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");

        assertThrows(BusinessException.class, () -> service.prepareSchemaDelete(schemaPrepare("app_db", "app_schema")));
        assertTrue(dbManager.droppedDatabases.isEmpty());
        assertTrue(dbManager.droppedSchemas.isEmpty());
    }

    @Test
    void preparesPostgreSqlSchemaDeleteWithDatabaseAndSchemaQualifiedName() {
        registerPlugin("POSTGRESQL", true, true,
                List.of(database("app_db")), List.of(schema("tenant_schema")));
        DbDatabaseObjectDeleteServiceImpl service = newService("POSTGRESQL");

        DatabaseObjectDeletePrepare result = service.prepareSchemaDelete(schemaPrepare("app_db", "tenant_schema"));

        assertEquals("tenant_schema", result.getConfirmName());
        assertEquals("DROP SCHEMA \"tenant_schema\"", result.getSqlPreview());
    }

    @Test
    void executeRequiresExactQualifiedConfirmationNameAndDoesNotDropOnMismatch() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("app_db")), List.of());
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");

        DbDatabaseObjectDeleteExecuteRequest request = databaseExecute("app_db", "other_db");

        assertThrows(BusinessException.class, () -> service.executeDatabaseDelete(request));
        assertTrue(dbManager.droppedDatabases.isEmpty());
    }

    @Test
    void executeDropsRequestedDatabaseAfterServerSideValidation() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("app_db")), List.of());
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");

        service.executeDatabaseDelete(databaseExecute("app_db", "app_db"));

        assertEquals(List.of("app_db"), dbManager.droppedDatabases);
    }

    @Test
    void executeDoesNotDropWhenRequestedDatabaseDoesNotExist() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("app_db")), List.of());
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");

        assertThrows(BusinessException.class, () -> service.executeDatabaseDelete(databaseExecute("other_db", "other_db")));
        assertTrue(dbManager.droppedDatabases.isEmpty());
    }

    @Test
    void databaseExistenceCheckIsCaseSensitive() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("App_DB")), List.of());
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");

        assertThrows(BusinessException.class, () -> service.prepareDatabaseDelete(databasePrepare("app_db")));
        assertTrue(dbManager.droppedDatabases.isEmpty());
    }

    @Test
    void databaseConfirmationNameIsCaseSensitiveAndDoesNotDropOnMismatch() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("App_DB")), List.of());
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");
        DatabaseObjectDeletePrepare prepared = service.prepareDatabaseDelete(databasePrepare("App_DB"));

        assertEquals("App_DB", prepared.getConfirmName());
        assertEquals("DROP DATABASE `App_DB`", prepared.getSqlPreview());
        assertThrows(BusinessException.class, () -> service.executeDatabaseDelete(databaseExecute("App_DB", "app_db")));
        assertTrue(dbManager.droppedDatabases.isEmpty());
    }

    @Test
    void dropsMixedCaseDatabaseOnlyWithExactConfirmationName() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("App_DB")), List.of());
        DbDatabaseObjectDeleteServiceImpl service = newService("MYSQL");

        service.executeDatabaseDelete(databaseExecute("App_DB", "App_DB"));

        assertEquals(List.of("App_DB"), dbManager.droppedDatabases);
    }

    @Test
    void schemaExistenceAndConfirmationAreCaseSensitive() {
        RecordingDBManager dbManager = registerPlugin("POSTGRESQL", true, true,
                List.of(database("App_DB")), List.of(schema("Tenant_Schema")));
        DbDatabaseObjectDeleteServiceImpl service = newService("POSTGRESQL");

        assertThrows(BusinessException.class, () -> service.prepareSchemaDelete(schemaPrepare("App_DB", "tenant_schema")));

        DatabaseObjectDeletePrepare prepared = service.prepareSchemaDelete(schemaPrepare("App_DB", "Tenant_Schema"));

        assertEquals("Tenant_Schema", prepared.getConfirmName());
        assertEquals("DROP SCHEMA \"Tenant_Schema\"", prepared.getSqlPreview());
        assertThrows(BusinessException.class, () -> service.executeSchemaDelete(
                schemaExecute("App_DB", "Tenant_Schema", "tenant_schema")));
        assertTrue(dbManager.droppedSchemas.isEmpty());
    }

    @Test
    void mySqlDatabaseDeleteRebindsFromTargetDatabaseToServerLevelConnection() {
        RecordingDBManager dbManager = registerPlugin("MYSQL", true, false,
                List.of(database("app_db")), List.of());
        RecordingConnectionContextService connectionContextService = new RecordingConnectionContextService(
                "MYSQL", "app_db");
        DbDatabaseObjectDeleteServiceImpl service = new DbDatabaseObjectDeleteServiceImpl(connectionContextService);

        service.executeDatabaseDelete(databaseExecute("app_db", "app_db"));

        assertEquals(List.of("app_db"), dbManager.droppedDatabases);
        assertNull(connectionContextService.currentProfile().getDatabaseName());
    }

    @Test
    void postgreSqlDatabaseDeleteConnectsToMaintenanceDatabaseInsteadOfTargetDatabase() {
        RecordingDBManager dbManager = registerPlugin("POSTGRESQL", true, true,
                List.of(database("app_db")), List.of());
        RecordingConnectionContextService connectionContextService = new RecordingConnectionContextService("POSTGRESQL");
        DbDatabaseObjectDeleteServiceImpl service = new DbDatabaseObjectDeleteServiceImpl(connectionContextService);

        service.executeDatabaseDelete(databaseExecute("app_db", "app_db"));

        assertEquals(List.of("app_db"), dbManager.droppedDatabases);
        assertEquals("postgres", connectionContextService.currentProfile().getDatabaseName());
    }

    @Test
    void postgreSqlSchemaDeleteConnectsToTargetDatabase() {
        RecordingDBManager dbManager = registerPlugin("POSTGRESQL", true, true,
                List.of(database("app_db")), List.of(schema("tenant_schema")));
        RecordingConnectionContextService connectionContextService = new RecordingConnectionContextService(
                "POSTGRESQL", "app_db");
        DbDatabaseObjectDeleteServiceImpl service = new DbDatabaseObjectDeleteServiceImpl(connectionContextService);

        service.executeSchemaDelete(schemaExecute("app_db", "tenant_schema", "tenant_schema"));

        assertEquals(List.of("app_db.tenant_schema"), dbManager.droppedSchemas);
        assertEquals("app_db", connectionContextService.currentProfile().getDatabaseName());
    }

    private static DbDatabaseObjectDeleteServiceImpl newService(String dbType) {
        return new DbDatabaseObjectDeleteServiceImpl(new RecordingConnectionContextService(dbType));
    }

    private static DbDatabaseDeletePrepareRequest databasePrepare(String databaseName) {
        DbDatabaseDeletePrepareRequest request = new DbDatabaseDeletePrepareRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName(databaseName);
        return request;
    }

    private static DbSchemaDeletePrepareRequest schemaPrepare(String databaseName, String schemaName) {
        DbSchemaDeletePrepareRequest request = new DbSchemaDeletePrepareRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName(databaseName);
        request.setSchemaName(schemaName);
        return request;
    }

    private static DbDatabaseObjectDeleteExecuteRequest databaseExecute(String databaseName, String confirmName) {
        DbDatabaseObjectDeleteExecuteRequest request = new DbDatabaseObjectDeleteExecuteRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName(databaseName);
        request.setConfirmName(confirmName);
        return request;
    }

    private static DbDatabaseObjectDeleteExecuteRequest schemaExecute(String databaseName, String schemaName,
                                                                    String confirmName) {
        DbDatabaseObjectDeleteExecuteRequest request = new DbDatabaseObjectDeleteExecuteRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName(databaseName);
        request.setSchemaName(schemaName);
        request.setConfirmName(confirmName);
        return request;
    }

    private static RecordingDBManager registerPlugin(String dbType, boolean supportDatabase, boolean supportSchema,
                                                    List<Database> databases, List<Schema> schemas) {
        DBConfig dbConfig = new DBConfig();
        dbConfig.setDbType(dbType);
        dbConfig.setDefaultDriverConfig(new DriverConfig());
        dbConfig.setSupportDatabase(supportDatabase);
        dbConfig.setSupportSchema(supportSchema);
        RecordingDBManager dbManager = new RecordingDBManager();
        IDbMetaData metaData = new StaticMetaData(dbType, databases, schemas);
        Chat2DBContext.PLUGIN_MAP.put(dbType, new StaticPlugin(dbConfig, metaData, dbManager));
        return dbManager;
    }

    private static Database database(String name) {
        Database database = new Database();
        database.setName(name);
        return database;
    }

    private static Schema schema(String name) {
        Schema schema = new Schema();
        schema.setName(name);
        return schema;
    }

    private static class RecordingConnectionContextService implements IDbConnectionContextService {
        private final String dbType;
        private ConnectionProfile profile;

        private RecordingConnectionContextService(String dbType) {
            this(dbType, null);
        }

        private RecordingConnectionContextService(String dbType, String databaseName) {
            this.dbType = dbType;
            this.profile = profile(databaseName, null);
        }

        @Override
        public void bind(DbConnectionContextRequest param) {
            bindProfile(buildProfile(param));
        }

        @Override
        public ConnectionProfile buildProfile(DbConnectionContextRequest param) {
            return profile(param.getDatabaseName(), param.getSchemaName());
        }

        @Override
        public void bindProfile(ConnectionProfile profile) {
            this.profile = profile;
            ConnectInfo connectInfo = toConnectInfo(profile);
            Chat2DBContext.putContext(connectInfo);
        }

        @Override
        public void bindMcp(McpConnectionContextRequest param) {
        }

        @Override
        public void clear() {
            Chat2DBContext.removeContext();
        }

        @Override
        public void rebindCurrentDatabase(String databaseName) {
            bindProfile(profile(databaseName, profile.getSchemaName()));
        }

        @Override
        public void close() {
            clear();
        }

        @Override
        public void releaseAllBoundTransactions() {
            // No-op mock: the delete flow does not open console-bound transactions.
        }

        @Override
        public ConnectionProfile currentProfile() {
            if (Chat2DBContext.getConnectInfo() == null) {
                bindProfile(profile);
            }
            return profile;
        }

        @Override
        public ConnectionProfile currentProfileSnapshot() {
            return currentProfile();
        }

        @Override
        public DriverConfig getDefaultDriverConfig(String dbType) {
            return new DriverConfig();
        }

        @Override
        public boolean supportCrossDatabase() {
            return false;
        }

        @Override
        public boolean supportCrossSchema() {
            return false;
        }

        @Override
        public boolean supportDatabase() {
            return false;
        }

        @Override
        public boolean supportSchema() {
            return false;
        }

        @Override
        public List<String> getSystemDatabases(String dbType) {
            return List.of();
        }

        @Override
        public List<String> getSystemSchemas(String dbType) {
            return List.of();
        }

        @Override
        public List<ForeignKeyInfo> getImportedKeys(String databaseName, String schemaName, String tableName) {
            return List.of();
        }

        @Override
        public List<Table> queryObjects(DbObjectsQueryRequest queryObjectsRequest) {
            return List.of();
        }

        private ConnectionProfile profile(String databaseName, String schemaName) {
            ConnectionProfile connectionProfile = new ConnectionProfile();
            connectionProfile.setDataSourceId(7L);
            connectionProfile.setDbType(dbType);
            connectionProfile.setDatabaseName(databaseName);
            connectionProfile.setSchemaName(schemaName);
            return connectionProfile;
        }

        private ConnectInfo toConnectInfo(ConnectionProfile profile) {
            ConnectInfo connectInfo = new ConnectInfo();
            connectInfo.setDataSourceId(profile.getDataSourceId());
            connectInfo.setDbType(profile.getDbType());
            connectInfo.setDriverConfig(new DriverConfig());
            connectInfo.setDatabaseName(profile.getDatabaseName());
            connectInfo.setSchemaName(profile.getSchemaName());
            connectInfo.setConnection(fakeConnection());
            return connectInfo;
        }

        private Connection fakeConnection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> null;
                        case "isClosed" -> false;
                        case "toString" -> "fake-connection";
                        default -> null;
                    });
        }
    }

    private static class StaticPlugin implements IPlugin {
        private final DBConfig dbConfig;
        private final IDbMetaData metaData;
        private final IDbManager dbManager;

        private StaticPlugin(DBConfig dbConfig, IDbMetaData metaData, IDbManager dbManager) {
            this.dbConfig = dbConfig;
            this.metaData = metaData;
            this.dbManager = dbManager;
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

    private static class StaticMetaData extends DefaultMetaService {
        private final String dbType;
        private final List<Database> databases;
        private final List<Schema> schemas;

        private StaticMetaData(String dbType, List<Database> databases, List<Schema> schemas) {
            this.dbType = dbType;
            this.databases = databases;
            this.schemas = schemas;
        }

        @Override
        public ISqlBuilder getSqlBuilder() {
            return new DefaultSqlBuilder() {
                @Override
                public String quoteIdentifier(String identifier) {
                    return switch (dbType) {
                        case "MYSQL" -> "`" + identifier + "`";
                        case "POSTGRESQL" -> "\"" + identifier + "\"";
                        default -> super.quoteIdentifier(identifier);
                    };
                }
            };
        }

        @Override
        public List<Database> databases(Connection connection) {
            return databases;
        }

        @Override
        public List<Schema> schemas(Connection connection, String databaseName) {
            return schemas;
        }
    }

    private static class RecordingDBManager extends DefaultDBManager {
        private final List<String> droppedDatabases = new ArrayList<>();
        private final List<String> droppedSchemas = new ArrayList<>();

        @Override
        public void dropDatabase(Connection connection, String databaseName) {
            droppedDatabases.add(databaseName);
        }

        @Override
        public void dropSchema(Connection connection, String databaseName, String schemaName) {
            droppedSchemas.add(databaseName + "." + schemaName);
        }
    }
}
