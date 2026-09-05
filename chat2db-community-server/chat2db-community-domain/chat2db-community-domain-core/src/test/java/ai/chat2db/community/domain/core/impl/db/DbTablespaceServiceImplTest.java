package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.db.TablespaceCapability;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceModifyRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceQueryRequest;
import ai.chat2db.community.domain.core.cache.CacheManage;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ai.chat2db.community.domain.core.cache.CacheKey.getTablespacesKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbTablespaceServiceImplTest {

    private static final String DB_TYPE = "TABLESPACE_TEST";
    private static final long DATA_SOURCE_ID = 9_263_400L;

    private final AtomicReference<List<Tablespace>> currentTablespaces = new AtomicReference<>(List.of());
    private final AtomicInteger metadataCalls = new AtomicInteger();
    private final RecordingManager dbManager = new RecordingManager();
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        config.setSupportTablespace(true);
        IDbMetaData metadata = new DefaultMetaService() {
            @Override
            public List<Tablespace> tablespaces(Connection connection) {
                metadataCalls.incrementAndGet();
                return currentTablespaces.get();
            }

            @Override
            public Tablespace tablespace(Connection connection, String tablespaceName) {
                return currentTablespaces.get().stream()
                        .filter(value -> tablespaceName.equals(value.getName()))
                        .findFirst()
                        .orElse(null);
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

            @Override
            public IDbManager getDbManager() {
                return dbManager;
            }
        });
        CacheManage.remove(getTablespacesKey(DATA_SOURCE_ID));
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        CacheManage.remove(getTablespacesKey(DATA_SOURCE_ID));
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
    }

    @Test
    void capabilityUsesTheCurrentlyBoundDatasourceVersion() {
        DbTablespaceServiceImpl service = new DbTablespaceServiceImpl();

        bind("5.7.44");
        TablespaceCapability mysql57 = service.capability(query(DATA_SOURCE_ID));
        Chat2DBContext.removeContext();
        bind("8.0.36");
        TablespaceCapability mysql80 = service.capability(query(DATA_SOURCE_ID + 1));

        assertEquals("5.7.44", mysql57.getServerVersion());
        assertTrue(mysql57.isManageSupported());
        assertFalse(mysql57.isRenameSupported());
        assertEquals("8.0.36", mysql80.getServerVersion());
        assertTrue(mysql80.isManageSupported());
        assertTrue(mysql80.isRenameSupported());
    }

    @Test
    void renameIsRejectedWhenTheBoundDatasourceDoesNotSupportIt() {
        bind("5.7.44");
        DbTablespaceModifyRequest request = DbTablespaceModifyRequest.builder()
                .dataSourceId(DATA_SOURCE_ID)
                .oldName("ts_old")
                .newName("ts_new")
                .build();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbTablespaceServiceImpl().modifyTablespace(request));

        assertEquals("tablespace.rename.notSupported", exception.getCode());
        assertEquals(List.of(), dbManager.renames);
    }

    @Test
    void renameInvalidatesTheExactPersistentListCacheEntry() {
        bind("8.0.36");
        DbTablespaceServiceImpl service = new DbTablespaceServiceImpl();
        currentTablespaces.set(List.of(Tablespace.builder().name("ts_old").build()));

        assertEquals(List.of("ts_old"), names(service.queryAll(query(DATA_SOURCE_ID))));
        currentTablespaces.set(List.of(Tablespace.builder().name("ts_new").build()));
        service.modifyTablespace(DbTablespaceModifyRequest.builder()
                .dataSourceId(DATA_SOURCE_ID)
                .oldName("ts_old")
                .newName("ts_new")
                .build());

        assertEquals(List.of("ts_new"), names(service.queryAll(query(DATA_SOURCE_ID))));
        assertEquals(2, metadataCalls.get());
        assertEquals(List.of("ts_old->ts_new"), dbManager.renames);
    }

    @Test
    void metadataPoliciesHideServerPathsAndUnauthorizedOccupyingTables() {
        bind("8.0.36");
        currentTablespaces.set(List.of(Tablespace.builder()
                .name("ts_shared")
                .dataFiles(List.of("/var/lib/mysql/ts_shared.ibd"))
                .occupyingTables(List.of("app.visible_table", "app.secret_table"))
                .build()));
        MetadataAccessPolicyManager policyManager = new MetadataAccessPolicyManager(List.of(resources -> resources
                .stream()
                .map(resource -> "visible_table".equals(resource.getTableName()))
                .toList()));
        DbTablespaceServiceImpl service = new DbTablespaceServiceImpl(policyManager);
        DbTablespaceQueryRequest request = query(DATA_SOURCE_ID);
        request.setTablespaceName("ts_shared");

        Tablespace result = service.query(request);

        assertNull(result.getDataFiles());
        assertEquals(List.of("app.visible_table"), result.getOccupyingTables());
    }

    private void bind(String version) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(DATA_SOURCE_ID);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDbVersion(version);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection());
        Chat2DBContext.putContext(connectInfo);
    }

    private DbTablespaceQueryRequest query(long dataSourceId) {
        return DbTablespaceQueryRequest.builder()
                .dataSourceId(dataSourceId)
                .dbType(DB_TYPE)
                .connection(connection())
                .build();
    }

    private List<String> names(List<Tablespace> tablespaces) {
        return tablespaces.stream().map(Tablespace::getName).toList();
    }

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "TablespaceTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static final class RecordingManager extends DefaultDBManager {
        private final List<String> renames = new ArrayList<>();

        @Override
        public boolean supportsTablespaceManagement() {
            return Chat2DBContext.getDbVersion().startsWith("5.7")
                    || Chat2DBContext.getDbVersion().startsWith("8.");
        }

        @Override
        public boolean supportsTablespaceRename() {
            return Chat2DBContext.getDbVersion().startsWith("8.");
        }

        @Override
        public void alterTablespaceRename(Connection connection, String oldTablespaceName,
                                          String newTablespaceName) {
            renames.add(oldTablespaceName + "->" + newTablespaceName);
        }
    }
}
