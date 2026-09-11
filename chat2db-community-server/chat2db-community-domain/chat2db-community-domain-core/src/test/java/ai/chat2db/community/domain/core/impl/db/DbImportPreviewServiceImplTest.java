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

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.ISQLIdentifierProcessor;
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
    void previewCanonicalizesTableCaseWithoutLosingTargetColumns(@TempDir Path directory)
            throws Exception {
        ImportPreview preview = service()
                .preview(DATA_SOURCE_ID, DATABASE, null, "ORDERS", csv(directory));

        assertEquals(1, metaData.requests.size());
        TableMetadataRequest request = metaData.requests.get(0);
        assertEquals(DATABASE, request.getDatabaseName());
        assertEquals(null, request.getSchemaName());
        assertEquals("orders", request.getTableName());
        assertEquals("orders", preview.getTargetTableName());
        assertEquals(1, preview.getTargetColumns().size());
        assertEquals("Contact name", preview.getTargetColumns().get(0).getComment());
    }

    @Test
    void previewAcceptsPunctuationInARealTableName(@TempDir Path directory) throws Exception {
        metaData.tableName = "order.items";

        ImportPreview preview = service()
                .preview(DATA_SOURCE_ID, DATABASE, null, "order.items", csv(directory));

        assertEquals("order.items", preview.getTargetTableName());
        assertEquals("order.items", metaData.requests.get(0).getTableName());
    }

    @Test
    void previewUsesDialectIdentifierProcessor(@TempDir Path directory) throws Exception {
        metaData.identifierProcessor = new DefaultSQLIdentifierProcessor() {
            @Override
            public String removeIdentifierQuote(String identifier) {
                return identifier != null && identifier.startsWith("<") && identifier.endsWith(">")
                        ? identifier.substring(1, identifier.length() - 1) : identifier;
            }
        };

        ImportPreview preview = service()
                .preview(DATA_SOURCE_ID, "<app>", null, "<orders>", csv(directory));

        assertEquals("orders", preview.getTargetTableName());
        assertEquals("orders", metaData.requests.get(0).getTableName());
    }

    @Test
    void previewUsesSchemaForDatabasesThatSupportSchemas(@TempDir Path directory) throws Exception {
        Chat2DBContext.getDBConfig().setSupportSchema(true);
        Chat2DBContext.getConnectInfo().setSchemaName("public");

        service()
                .preview(DATA_SOURCE_ID, DATABASE, "public", "orders", csv(directory));

        TableMetadataRequest request = metaData.requests.get(0);
        assertEquals(DATABASE, request.getDatabaseName());
        assertEquals("public", request.getSchemaName());
        assertEquals("orders", request.getTableName());
    }

    @Test
    void previewRejectsDatabaseMismatchBeforeMetadataLookup(@TempDir Path directory) throws Exception {
        assertThrows(BusinessException.class, () -> service()
                .preview(DATA_SOURCE_ID, "other", null, "orders", csv(directory)));

        assertEquals(0, metaData.tablesRequests);
        assertEquals(0, metaData.requests.size());
    }

    @Test
    void previewRejectsTableNameThatDoesNotExist(@TempDir Path directory) throws Exception {
        assertThrows(BusinessException.class, () -> service()
                .preview(DATA_SOURCE_ID, DATABASE, null, "orders%", csv(directory)));

        assertEquals(1, metaData.tablesRequests);
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

        ImportPreview preview = service()
                .preview(DATA_SOURCE_ID, DATABASE, null, "orders", path.toFile());

        assertEquals(10, preview.getPreviewLimit());
        assertEquals(List.of("Name"), preview.getSourceColumns());
        assertEquals(10, preview.getPreviewData().size());
        assertEquals(List.of("row-1"), preview.getPreviewData().get(0));
        assertEquals(List.of("row-10"), preview.getPreviewData().get(9));
    }

    @Test
    void previewRejectsDuplicateSourceColumnsIgnoringCase(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("duplicate-columns.csv");
        Files.writeString(path, "Name,name\nAlice,Bob\n", StandardCharsets.UTF_8);

        assertThrows(BusinessException.class, () -> service()
                .preview(DATA_SOURCE_ID, DATABASE, null, "orders", path.toFile()));

        assertEquals(0, metaData.tablesRequests);
        assertEquals(0, metaData.requests.size());
    }

    @Test
    void previewMapsHeaderlessColumnsByPositionAndSkipsAutoIncrementTargets(@TempDir Path directory)
            throws Exception {
        metaData.columns = List.of(
                TableColumn.builder().name("id").columnType("BIGINT").autoIncrement(true).build(),
                TableColumn.builder().name("name").columnType("VARCHAR").build(),
                TableColumn.builder().name("email").columnType("VARCHAR").build());
        Path path = directory.resolve("headerless.csv");
        Files.writeString(path, "Alice,alice@example.com,extra\n", StandardCharsets.UTF_8);

        ImportPreview preview = service().preview(DATA_SOURCE_ID, DATABASE, null,
                "orders", path.toFile(), CsvOptions.builder().hasHeader(false).build());

        assertEquals(List.of("column_1", "column_2", "column_3"), preview.getSourceColumns());
        assertEquals(2, preview.getSuggestedMapping().size());
        assertEquals("column_1", preview.getSuggestedMapping().get(0).getSourceColumn());
        assertEquals("name", preview.getSuggestedMapping().get(0).getTargetColumn());
        assertEquals("column_2", preview.getSuggestedMapping().get(1).getSourceColumn());
        assertEquals("email", preview.getSuggestedMapping().get(1).getTargetColumn());
    }

    @Test
    void previewUsesCsvDelimiterEncodingAndHeaderOptions(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("custom.csv");
        Files.write(path, "Alice;olá\n".getBytes(java.nio.charset.Charset.forName("ISO-8859-1")));
        CsvOptions options = CsvOptions.builder()
                .encoding("ISO-8859-1")
                .delimiter(";")
                .quote("\"")
                .escape("\"")
                .hasHeader(false)
                .emptyAsNull(false)
                .build();

        ImportPreview preview = service()
                .preview(DATA_SOURCE_ID, DATABASE, null, "orders", path.toFile(), options);

        assertEquals(List.of("column_1", "column_2"), preview.getSourceColumns());
        assertEquals(List.of("Alice", "olá"), preview.getPreviewData().get(0));
    }

    @Test
    void previewUsesConfiguredHeaderAndDataRowRange(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("row-range.csv");
        Files.writeString(path, "Generated report\nName,Note\nAlice,first\nBob,second\nFooter,ignored\n");
        CsvOptions options = CsvOptions.builder()
                .headerRow(2)
                .dataStartRow(3)
                .dataEndRow(4)
                .build();

        ImportPreview preview = service()
                .preview(DATA_SOURCE_ID, DATABASE, null, "orders", path.toFile(), options);

        assertEquals(List.of("Name", "Note"), preview.getSourceColumns());
        assertEquals(List.of(List.of("Alice", "first"), List.of("Bob", "second")), preview.getPreviewData());
    }

    private File csv(Path directory) throws Exception {
        Path path = directory.resolve("orders.csv");
        Files.writeString(path, "Name\nAlice\n", StandardCharsets.UTF_8);
        return path.toFile();
    }

    private DbImportPreviewServiceImpl service() {
        return new DbImportPreviewServiceImpl(new ImportPreviewFileParser());
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
        private String tableName = "orders";
        private ISQLIdentifierProcessor identifierProcessor = new DefaultSQLIdentifierProcessor();
        private List<TableColumn> columns = List.of(TableColumn.builder().name("name").columnType("VARCHAR")
                .dataType(Types.VARCHAR).comment("Contact name").build());

        @Override
        public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
            return identifierProcessor;
        }

        @Override
        public List<Table> tables(Connection connection, TablesRequest request) {
            tablesRequests++;
            return List.of(Table.builder().databaseName(request.getDatabaseName())
                    .schemaName(request.getSchemaName()).name(tableName).build());
        }

        @Override
        public List<TableColumn> columns(Connection connection, TableMetadataRequest request) {
            requests.add(request);
            return columns;
        }
    }
}
