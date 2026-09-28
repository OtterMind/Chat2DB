package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.db.DbDmlExportPlan;
import ai.chat2db.community.domain.api.model.request.db.DbDmlExportRequest;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.task.extension.ExportCellContext;
import ai.chat2db.community.domain.api.service.db.extension.ISqlExecutionPolicy;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDmlExportServicePolicyTest {

    private static final String DB_TYPE = "MYSQL";
    private static final String ORIGINAL_SQL = "SELECT id, email, secret FROM orders";
    private static final String AUTHORIZED_SQL = ORIGINAL_SQL + " WHERE tenant_id = 100";

    private IPlugin previousPlugin;
    private JdbcExecution jdbcExecution;
    private AtomicReference<SqlExecutionContext> authorizedContext;
    private AtomicReference<ExportCellContext> processedEmailContext;
    private AtomicInteger beforeExecuteCalls;
    private DbDmlExportServiceImpl service;

    @BeforeEach
    void setUp() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, plugin());
        jdbcExecution = new JdbcExecution();
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(58L);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDatabaseName("app");
        connectInfo.setConnection(jdbcExecution.connection());
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);

        authorizedContext = new AtomicReference<>();
        processedEmailContext = new AtomicReference<>();
        beforeExecuteCalls = new AtomicInteger();
        ISqlExecutionPolicy policy = new ISqlExecutionPolicy() {
            @Override
            public String rewriteSql(SqlExecutionContext context, String sql) {
                authorizedContext.set(context);
                assertEquals(ORIGINAL_SQL, sql);
                return AUTHORIZED_SQL;
            }

            @Override
            public Integer maxRows(SqlExecutionContext context, String sql) {
                return 2;
            }

            @Override
            public void beforeExecute(SqlExecutionPlan plan) {
                beforeExecuteCalls.incrementAndGet();
            }

            @Override
            public boolean includeColumn(
                    ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext context) {
                return !"secret".equalsIgnoreCase(context.getColumnName());
            }
        };
        ExportCellProcessorChain processorChain = new ExportCellProcessorChain(List.of((context, cell) -> {
            if (!"email".equalsIgnoreCase(context.getColumnName())) {
                return cell;
            }
            processedEmailContext.set(context);
            return "shop".equals(context.getDatabaseName()) ? cell.withValue("MASKED") : cell;
        }));
        service = new DbDmlExportServiceImpl(new SqlExecutionPolicyManager(List.of(policy)), processorChain);
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
    void csvExportReauthorizesTheSelectedOriginalSqlAndEnforcesEveryPolicy() throws Exception {
        ByteArrayOutputStream output = export("CSV");
        String csv = output.toString(StandardCharsets.UTF_8);

        assertExecutionContract();
        assertTrue(csv.contains("id,email"), csv);
        assertTrue(csv.contains("1,MASKED"), csv);
        assertTrue(csv.contains("2,MASKED"), csv);
        assertFalse(csv.contains("3,MASKED"), csv);
        assertFalse(csv.contains("TOP_SECRET"), csv);
        assertFalse(csv.toLowerCase().contains("secret"), csv);
    }

    @Test
    void xlsxExportEnforcesColumnsRowsAndCellProcessors() throws Exception {
        ByteArrayOutputStream output = export("EXCEL");

        assertExecutionContract();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(2, workbook.getSheetAt(0).getRow(0).getPhysicalNumberOfCells());
            assertEquals("id", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("email", workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
            assertEquals("MASKED", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("MASKED", workbook.getSheetAt(0).getRow(2).getCell(1).getStringCellValue());
            assertEquals(3, workbook.getSheetAt(0).getPhysicalNumberOfRows());
        }
    }

    @Test
    void insertExportEnforcesColumnsRowsAndCellProcessors() throws Exception {
        ByteArrayOutputStream output = export("INSERT");
        String sql = output.toString(StandardCharsets.UTF_8);

        assertExecutionContract();
        assertTrue(sql.contains("MASKED"), sql);
        assertTrue(sql.contains("shop.orders"), sql);
        assertFalse(sql.contains("app.orders"), sql);
        assertFalse(sql.contains("TOP_SECRET"), sql);
        assertFalse(sql.contains("third@example.com"), sql);
        assertEquals(2, sql.lines().filter(line -> !line.isBlank()).count(), sql);
    }

    @Test
    void csvExportRejectsDdlSql() {
        assertRejectsNonSelectExport("CSV", "CREATE TABLE orders (id INT)");
    }

    @Test
    void csvExportRejectsDmlSql() {
        assertRejectsNonSelectExport("CSV", "UPDATE orders SET email = 'blocked@example.com'");
    }

    @Test
    void xlsxExportRejectsDdlSql() {
        assertRejectsNonSelectExport("EXCEL", "DROP TABLE orders");
    }

    @Test
    void xlsxExportRejectsDmlSql() {
        assertRejectsNonSelectExport("EXCEL", "DELETE FROM orders");
    }

    @Test
    void csvExportRejectsNonSelectSqlWhenDatasourceIsNotDruidSupported() {
        assertRejectsNonSelectExportForDatasource("REDIS", "DEL export_guard");
    }

    @Test
    void csvExportRejectsNonSelectSqlWhenSyntaxPluginIsUnavailable() {
        assertRejectsNonSelectExportForDatasource("SNOWFLAKE", "DELETE FROM export_guard");
    }

    private void assertRejectsNonSelectExportForDatasource(String databaseType, String sql) {
        Chat2DBContext.getConnectInfo().setDbType(databaseType);
        DbDmlExportServiceImpl selectValidationService = selectValidationService();
        DbDmlExportRequest request = new DbDmlExportRequest();
        request.setSql(sql);
        request.setOriginalSql(sql);
        request.setExportSize("ALL");
        request.setExportType("CSV");
        request.setDatabaseName("shop");

        DbDmlExportPlan plan = selectValidationService.prepareExport(request);

        assertThrows(BusinessException.class,
                () -> selectValidationService.export(plan.getExportRequest(), new ByteArrayOutputStream(), null, () -> {},
                        ignored -> {}, () -> {}));
        assertNull(jdbcExecution.executedSql);
    }

    @Test
    void csvExportAcceptsSelectSql() throws Exception {
        ByteArrayOutputStream output = export("CSV", ORIGINAL_SQL);

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("id,email"));
    }

    @Test
    void xlsxExportAcceptsSelectSql() throws Exception {
        ByteArrayOutputStream output = export("EXCEL", ORIGINAL_SQL);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals("id", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
    }

    private ByteArrayOutputStream export(String exportType) throws Exception {
        return export(exportType, ORIGINAL_SQL);
    }

    private ByteArrayOutputStream export(String exportType, String originalSql) throws Exception {
        DbDmlExportRequest request = new DbDmlExportRequest();
        request.setSql("SELECT 1");
        request.setOriginalSql(originalSql);
        request.setExportSize("ALL");
        request.setExportType(exportType);
        request.setDatabaseName("shop");

        DbDmlExportPlan plan = service.prepareExport(request);
        assertEquals(originalSql, plan.getExportRequest().getSql());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export(plan.getExportRequest(), output, null, () -> {}, ignored -> {}, () -> {});
        return output;
    }

    private void assertRejectsNonSelectExport(String exportType, String originalSql) {
        DbDmlExportRequest request = new DbDmlExportRequest();
        request.setSql("SELECT 1");
        request.setOriginalSql(originalSql);
        request.setExportSize("ALL");
        request.setExportType(exportType);
        request.setDatabaseName("shop");

        DbDmlExportPlan plan = service.prepareExport(request);

        assertThrows(BusinessException.class,
                () -> selectValidationService().export(plan.getExportRequest(), new ByteArrayOutputStream(), null, () -> {},
                        ignored -> {}, () -> {}));
    }

    private DbDmlExportServiceImpl selectValidationService() {
        return new DbDmlExportServiceImpl(new SqlExecutionPolicyManager(List.of()), new ExportCellProcessorChain(List.of()));
    }

    private void assertExecutionContract() {
        assertEquals(ORIGINAL_SQL, authorizedContext.get().getOriginalSql());
        assertEquals(AUTHORIZED_SQL, jdbcExecution.executedSql);
        assertEquals(2, jdbcExecution.maxRows);
        assertEquals(2, jdbcExecution.nextCalls);
        assertEquals(1, beforeExecuteCalls.get());
        assertEquals("shop", processedEmailContext.get().getDatabaseName());
    }

    private static IPlugin plugin() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
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

    private static final class JdbcExecution {
        private final List<String> columns = List.of("id", "email", "secret");
        private final List<List<String>> rows = List.of(
                List.of("1", "first@example.com", "TOP_SECRET_1"),
                List.of("2", "second@example.com", "TOP_SECRET_2"),
                List.of("3", "third@example.com", "TOP_SECRET_3"));
        private String executedSql;
        private int maxRows;
        private int nextCalls;

        private Connection connection() {
            return proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    executedSql = (String) args[0];
                    yield statement();
                }
                case "isClosed" -> false;
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement() {
            ResultSet resultSet = resultSet();
            return proxy(PreparedStatement.class, (target, method, args) -> switch (method.getName()) {
                case "setMaxRows" -> {
                    maxRows = (Integer) args[0];
                    yield null;
                }
                case "execute" -> true;
                case "getResultSet" -> resultSet;
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet() {
            AtomicInteger rowIndex = new AtomicInteger(-1);
            return proxy(ResultSet.class, (target, method, args) -> switch (method.getName()) {
                case "next" -> {
                    nextCalls++;
                    yield rowIndex.incrementAndGet() < rows.size();
                }
                case "getMetaData" -> metadata();
                case "getObject", "getString" -> rows.get(rowIndex.get()).get((Integer) args[0] - 1);
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSetMetaData metadata() {
            return proxy(ResultSetMetaData.class, (target, method, args) -> switch (method.getName()) {
                case "getColumnCount" -> columns.size();
                case "getColumnName", "getColumnLabel" -> columns.get((Integer) args[0] - 1);
                case "getColumnType" -> Types.VARCHAR;
                case "getColumnTypeName" -> "VARCHAR";
                case "getPrecision" -> 255;
                case "getScale" -> 0;
                case "getTableName" -> "orders";
                case "getCatalogName" -> "shop";
                case "getSchemaName" -> "";
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(target, method, args);
            }
            if ("unwrap".equals(method.getName())) {
                return null;
            }
            if ("isWrapperFor".equals(method.getName())) {
                return false;
            }
            return handler.invoke(target, method, args);
        });
        return type.cast(proxy);
    }

    private static Object objectMethod(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> target.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
