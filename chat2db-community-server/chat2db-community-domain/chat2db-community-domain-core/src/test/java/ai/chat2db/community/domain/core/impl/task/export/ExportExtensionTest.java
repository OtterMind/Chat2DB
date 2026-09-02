package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.task.extension.ExportCellContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.service.db.extension.ISqlExecutionPolicy;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.excel.CsvDataExporter;
import ai.chat2db.community.domain.core.impl.task.export.excel.XlsDataExporter;
import ai.chat2db.community.domain.core.impl.task.export.excel.XlsxDataExporter;
import ai.chat2db.community.domain.core.impl.task.export.json.JsonDataExporter;
import ai.chat2db.community.domain.core.impl.task.export.sql.SqlDataExporter;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportExtensionTest {

    @TempDir
    Path tempDir;

    @Test
    void processorsRunInInjectedOrder() {
        List<String> values = new ArrayList<>();
        ExportCellProcessorChain chain = new ExportCellProcessorChain(List.of(
                (context, cell) -> {
                    values.add(String.valueOf(cell.getValue()));
                    return cell.withValue("first");
                },
                (context, cell) -> {
                    values.add(String.valueOf(cell.getValue()));
                    return cell.withValue("second");
                }));

        ExportCell processed = chain.process(cellContext(),
                new ExportCell("raw", Types.VARCHAR, "VARCHAR", 20, 0));

        assertEquals(List.of("raw", "first"), values);
        assertEquals("second", processed.getValue());
    }

    @Test
    void baseExporterBuildsStructuredCellContextFromTaskSpec() throws Exception {
        AtomicReference<ExportCellContext> capturedContext = new AtomicReference<>();
        AtomicReference<Object> capturedValue = new AtomicReference<>();
        ExportCellProcessorChain chain = new ExportCellProcessorChain(List.of((context, cell) -> {
            capturedContext.set(context);
            capturedValue.set(cell.getValue());
            return cell.withValue("masked");
        }));
        TestExporter exporter = new TestExporter(chain);
        BigDecimal rawValue = new BigDecimal("123.4500");

        ExportCell processed = exporter.process(spec("test"), metadata(), "orders", jdbcValue(rawValue));

        assertSame(rawValue, capturedValue.get());
        assertEquals("masked", processed.getValue());
        assertEquals(7L, capturedContext.get().getDataSourceId());
        assertEquals("shop", capturedContext.get().getDatabaseName());
        assertEquals("orders", capturedContext.get().getTableName());
        assertEquals("email", capturedContext.get().getColumnName());
        assertEquals("test", capturedContext.get().getExportType());
    }

    @Test
    void registryContainsAllCommunityFormatsAndRejectsDuplicates() {
        ExportCellProcessorChain chain = new ExportCellProcessorChain(List.of());
        SqlExecutionPolicyManager policyManager = policyManager();
        ExportStrategyRegistry registry = new ExportStrategyRegistry(List.of(
                new CsvDataExporter(chain, policyManager), new XlsDataExporter(chain, policyManager),
                new XlsxDataExporter(chain, policyManager), new JsonDataExporter(chain, policyManager),
                new SqlDataExporter(chain, policyManager)));

        assertEquals("csv", registry.getExporter("CSV").type());
        assertEquals("xls", registry.getExporter("xls").type());
        assertEquals("xlsx", registry.getExporter("xlsx").type());
        assertEquals("json", registry.getExporter("json").type());
        assertEquals("sql", registry.getExporter("sql").type());
        assertThrows(ParamBusinessException.class, () -> registry.getExporter("xml"));
        assertThrows(IllegalStateException.class,
                () -> new ExportStrategyRegistry(List.of(strategy("csv"), strategy("CSV"))));
    }

    @Test
    void sharedExportRowCursorEnforcesBudgetAndCheckpoints() throws Exception {
        AtomicInteger checkpointCalls = new AtomicInteger();
        SqlExecutionPolicyManager policyManager = new SqlExecutionPolicyManager(List.of(new ISqlExecutionPolicy() {
            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return BaseExporter.EXPORT_BATCH_ROWS * 2;
            }

            @Override
            public void checkpoint(SqlExecutionPlan plan) {
                checkpointCalls.incrementAndGet();
            }
        }));
        TestExporter exporter = new TestExporter(new ExportCellProcessorChain(List.of()), policyManager);
        SqlExecutionPlan plan = policyManager.plan(new SqlExecutionContext(7L, "MYSQL", "shop", null, "orders",
                "select * from orders", SqlExecutionOperation.EXPORT, "test"));
        AtomicInteger nextCalls = new AtomicInteger();
        ResultSet resultSet = alwaysHasNextResultSet(nextCalls);

        assertTrue(exporter.next(resultSet, plan, 0));
        assertTrue(exporter.next(resultSet, plan, BaseExporter.EXPORT_BATCH_ROWS));
        assertFalse(exporter.next(resultSet, plan, BaseExporter.EXPORT_BATCH_ROWS * 2));

        assertEquals(1, checkpointCalls.get());
        assertEquals(2, nextCalls.get());
    }

    @Test
    void sharedColumnSelectionUsesTheSqlPolicyForJdbcMetadata() throws Exception {
        SqlExecutionPolicyManager policyManager = new SqlExecutionPolicyManager(List.of(new ISqlExecutionPolicy() {
            @Override
            public boolean includeColumn(
                    ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext context) {
                return !"secret".equalsIgnoreCase(context.getColumnName());
            }
        }));
        TestExporter exporter = new TestExporter(new ExportCellProcessorChain(List.of()), policyManager);
        SqlExecutionPlan plan = policyManager.plan(new SqlExecutionContext(7L, "MYSQL", "shop", null, "orders",
                "select * from orders", SqlExecutionOperation.EXPORT, "test"));

        assertEquals(List.of(1, 3), exporter.included(restrictedMetadata(), plan));
    }

    @Test
    void allFiveExportFormatsApplyPolicyAndExcludeRestrictedColumns() throws Exception {
        String dbType = "EXPORT_EXTENSION_TEST";
        AtomicInteger beforeExecuteCalls = new AtomicInteger();
        IPlugin previousPlugin = Chat2DBContext.PLUGIN_MAP.put(dbType, plugin(dbType));
        try (Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:export_extension;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS shop CASCADE");
                statement.execute("CREATE SCHEMA shop");
                statement.execute("CREATE TABLE shop.orders (id INT PRIMARY KEY, secret VARCHAR(64))");
                statement.execute("INSERT INTO shop.orders (id, secret) VALUES (1, 'TOP_SECRET')");
            }
            ConnectInfo connectInfo = new ConnectInfo();
            connectInfo.setDataSourceId(7L);
            connectInfo.setDbType(dbType);
            connectInfo.setConnection(connection);
            connectInfo.setDriverConfig(new DriverConfig());
            Chat2DBContext.putContext(connectInfo);

            SqlExecutionPolicyManager policyManager = new SqlExecutionPolicyManager(List.of(
                    new ISqlExecutionPolicy() {
                        @Override
                        public void beforeExecute(SqlExecutionPlan plan) {
                            beforeExecuteCalls.incrementAndGet();
                        }

                        @Override
                        public boolean includeColumn(
                                ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext context) {
                            return !"secret".equalsIgnoreCase(context.getColumnName());
                        }
                    }));
            ExportCellProcessorChain processorChain = new ExportCellProcessorChain(List.of());
            List<IExportStrategy> exporters = List.of(new CsvDataExporter(processorChain, policyManager),
                    new XlsDataExporter(processorChain, policyManager),
                    new XlsxDataExporter(processorChain, policyManager),
                    new JsonDataExporter(processorChain, policyManager),
                    new SqlDataExporter(processorChain, policyManager));

            int expectedBeforeExecuteCalls = 0;
            for (IExportStrategy exporter : exporters) {
                File output = tempDir.resolve("orders." + exporter.type()).toFile();
                exporter.run(spec(exporter.type()), new NoopTaskContext(), output);

                assertTrue(output.isFile(), exporter.type());
                assertRestrictedValueAbsent(output, exporter.type());
                assertEquals(++expectedBeforeExecuteCalls, beforeExecuteCalls.get(), exporter.type());
            }
        } finally {
            Chat2DBContext.removeContext();
            if (previousPlugin == null) {
                Chat2DBContext.PLUGIN_MAP.remove(dbType);
            } else {
                Chat2DBContext.PLUGIN_MAP.put(dbType, previousPlugin);
            }
        }
    }

    private static ExportTaskSpec spec(String format) {
        return ExportTaskSpec.builder()
                .target(TaskTargetSnapshot.builder().dataSourceId(7L).databaseName("shop").build())
                .tableNames(List.of("orders"))
                .format(format)
                .containsHeader(true)
                .build();
    }

    private static ExportCellContext cellContext() {
        return new ExportCellContext(7L, "MYSQL", "shop", null, "orders", "email", "csv");
    }

    private static ResultSetMetaData metadata() {
        return (ResultSetMetaData) Proxy.newProxyInstance(ExportExtensionTest.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnType" -> Types.VARCHAR;
                    case "getColumnTypeName" -> "VARCHAR";
                    case "getPrecision" -> 20;
                    case "getScale" -> 0;
                    case "getColumnName" -> "email";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSetMetaData restrictedMetadata() {
        List<String> columns = List.of("id", "secret", "created_at");
        return (ResultSetMetaData) Proxy.newProxyInstance(ExportExtensionTest.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> columns.size();
                    case "getColumnName", "getColumnLabel" -> columns.get((Integer) args[0] - 1);
                    case "getColumnType" -> Types.VARCHAR;
                    case "getColumnTypeName" -> "VARCHAR";
                    case "getTableName" -> "orders";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet alwaysHasNextResultSet(AtomicInteger nextCalls) {
        return (ResultSet) Proxy.newProxyInstance(ExportExtensionTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        nextCalls.incrementAndGet();
                        return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IPlugin plugin(String dbType) {
        DBConfig config = new DBConfig();
        config.setDbType(dbType);
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metaData = new DefaultMetaService();
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

    private static void assertRestrictedValueAbsent(File output, String type) throws Exception {
        if ("xls".equals(type) || "xlsx".equals(type)) {
            try (Workbook workbook = WorkbookFactory.create(output)) {
                assertEquals(1, workbook.getSheetAt(0).getRow(0).getPhysicalNumberOfCells(), type);
                assertEquals(1, workbook.getSheetAt(0).getRow(1).getPhysicalNumberOfCells(), type);
            }
            return;
        }
        String content = Files.readString(output.toPath());
        assertFalse(content.contains("TOP_SECRET"), type);
        assertFalse(content.toLowerCase().contains("secret"), type);
        assertTrue(content.contains("1"), type);
    }

    private static JDBCDataValue jdbcValue(Object value) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(ExportExtensionTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getObject" -> value;
                    case "getString" -> value == null ? null : String.valueOf(value);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new JDBCDataValue(resultSet, metadata(), 1, false);
    }

    private static IExportStrategy strategy(String type) {
        return new IExportStrategy() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void run(ExportTaskSpec spec, TaskExecutionContext context, File outputFile) {
            }
        };
    }

    private static SqlExecutionPolicyManager policyManager() {
        return new SqlExecutionPolicyManager(List.of());
    }

    private static final class TestExporter extends BaseExporter {

        private TestExporter(ExportCellProcessorChain chain) {
            this(chain, policyManager());
        }

        private TestExporter(ExportCellProcessorChain chain, SqlExecutionPolicyManager policyManager) {
            super(chain, policyManager);
        }

        private ExportCell process(ExportTaskSpec spec, ResultSetMetaData metadata, String tableName,
                JDBCDataValue value) throws Exception {
            return processJdbcCell(spec, metadata, 1, tableName, value);
        }

        private boolean next(ResultSet resultSet, SqlExecutionPlan plan, int exportedRowCount) throws Exception {
            return nextRow(resultSet, plan, exportedRowCount);
        }

        private List<Integer> included(ResultSetMetaData metadata, SqlExecutionPlan plan) throws Exception {
            return includedJdbcColumns(metadata, plan);
        }

        @Override
        public String type() {
            return "test";
        }

        @Override
        protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
                java.io.OutputStream output, boolean resuming) {
        }
    }

    private static final class NoopTaskContext implements TaskExecutionContext {

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName, String mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }
}
