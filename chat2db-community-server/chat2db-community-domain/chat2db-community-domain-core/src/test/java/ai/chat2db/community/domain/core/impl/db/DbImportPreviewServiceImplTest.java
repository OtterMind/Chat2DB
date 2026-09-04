package ai.chat2db.community.domain.core.impl.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbImportPreviewServiceImplTest {

    private static final String DB_TYPE = "IMPORT_PREVIEW_METADATA_TEST";
    private static final long DATA_SOURCE_ID = 930_101L;
    private static final String DATABASE = "app";

    private final RecordingMetaData metaData = new RecordingMetaData();

    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        config.setSupportDatabase(true);
        config.setSupportSchema(false);
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, plugin(config));
        Chat2DBContext.putContext(connectInfo());
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
    }

    @Test
    void previewCanonicalizesMysqlQualifiedTableWithoutLosingTargetColumns(@TempDir Path directory)
            throws Exception {
        Map<String, Object> preview = new DbImportPreviewServiceImpl()
                .preview(DATA_SOURCE_ID, DATABASE, "`app`.`orders`", csv(directory));

        assertEquals(1, metaData.requests.size());
        TableMetadataRequest request = metaData.requests.get(0);
        assertEquals(DATABASE, request.getDatabaseName());
        assertEquals(null, request.getSchemaName());
        assertEquals("orders", request.getTableName());
        assertEquals(1, ((List<?>) preview.get("targetColumns")).size());
    }

    @Test
    void previewRejectsDatabaseMismatchBeforeMetadataLookup(@TempDir Path directory) throws Exception {
        assertThrows(BusinessException.class, () -> new DbImportPreviewServiceImpl()
                .preview(DATA_SOURCE_ID, "other", "orders", csv(directory)));

        assertEquals(0, metaData.tablesRequests);
        assertEquals(0, metaData.requests.size());
    }

    @Test
    void previewRejectsWildcardTableBeforeMetadataLookup(@TempDir Path directory) throws Exception {
        assertThrows(BusinessException.class, () -> new DbImportPreviewServiceImpl()
                .preview(DATA_SOURCE_ID, DATABASE, "orders%", csv(directory)));

        assertEquals(0, metaData.tablesRequests);
        assertEquals(0, metaData.requests.size());
    }

    @Test
    void previewKeepsOnlyTheConfiguredNumberOfDataRows(@TempDir Path directory) throws Exception {
        StringBuilder content = new StringBuilder("Name\n");
        for (int row = 1; row <= 100; row++) {
            content.append("row-").append(row).append('\n');
        }
        Path path = directory.resolve("large-orders.csv");
        Files.writeString(path, content, StandardCharsets.UTF_8);

        Map<String, Object> preview = new DbImportPreviewServiceImpl()
                .preview(DATA_SOURCE_ID, DATABASE, "orders", path.toFile());

        assertEquals(50, preview.get("previewRows"));
        List<?> sourceColumns = (List<?>) preview.get("sourceColumns");
        Map<?, ?> sourceColumn = (Map<?, ?>) sourceColumns.get(0);
        assertEquals(50, ((List<?>) sourceColumn.get("sampleValues")).size());
    }

    private File csv(Path directory) throws Exception {
        Path path = directory.resolve("orders.csv");
        Files.writeString(path, "Name\nAlice\n", StandardCharsets.UTF_8);
        return path.toFile();
    }

    private IPlugin plugin(DBConfig config) {
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

    private ConnectInfo connectInfo() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(DATA_SOURCE_ID);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDatabaseName(DATABASE);
        connectInfo.setConnection(connection());
        connectInfo.setDriverConfig(new DriverConfig());
        return connectInfo;
    }

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "ImportPreviewMetadataTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static final class RecordingMetaData extends DefaultMetaService {

        private final List<TableMetadataRequest> requests = new ArrayList<>();
        private int tablesRequests;

        @Override
        public List<Table> tables(Connection connection, TablesRequest request) {
            tablesRequests++;
            return List.of(Table.builder().databaseName(request.getDatabaseName())
                    .schemaName(request.getSchemaName()).name("orders").build());
        }

        @Override
        public List<TableColumn> columns(Connection connection, TableMetadataRequest request) {
            requests.add(request);
            return List.of(TableColumn.builder().name("name").columnType("VARCHAR")
                    .dataType(Types.VARCHAR).build());
        }
    }
}
