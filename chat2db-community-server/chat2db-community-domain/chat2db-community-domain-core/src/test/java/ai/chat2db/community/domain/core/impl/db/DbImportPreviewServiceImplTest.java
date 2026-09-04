package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbImportPreviewServiceImplTest {

    private static final String DB_TYPE = "MAPPED_IMPORT_TEST";

    @TempDir
    Path tempDir;

    private IPlugin previousPlugin;

    @AfterEach
    void clearContext() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
            previousPlugin = null;
        }
    }

    @Test
    void previewParserStopsAtTheBoundWithoutDecodingLaterRows() throws Exception {
        Path file = importFile("bounded.csv");
        byte[] bytes = "name\nAda\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] invalidLaterRow = java.util.Arrays.copyOf(bytes, bytes.length + 2);
        invalidLaterRow[invalidLaterRow.length - 2] = (byte) 0xC3;
        invalidLaterRow[invalidLaterRow.length - 1] = (byte) 0x28;
        Files.write(file, invalidLaterRow);

        Object outcome = parseRows(file, 2, Map.of("encoding", "UTF-8"));

        assertEquals(2, rows(outcome).size());
    }

    @Test
    void defaultStrategyBindsOnlyColumnsIncludedInTheInsertStatement() throws Exception {
        List<Map<String, Object>> targetColumns = List.of(
                target("id", "BIGINT", false, true, null),
                target("name", "VARCHAR", false, false, null),
                target("created_at", "TIMESTAMP", false, false, "CURRENT_TIMESTAMP"));
        Map<Integer, String> sourceToTarget = Map.of(0, "name");
        Map<Integer, ExcelParser.CellValue> row = Map.of(0, new ExcelParser.CellValue("Ada", "string"));
        List<String> calls = new ArrayList<>();
        PreparedStatement statement = recordingStatement(calls);

        bindRow(statement, row, targetColumns, sourceToTarget, "DEFAULT");

        assertEquals(List.of("setString:1:Ada"), calls);
    }

    @Test
    void nullStrategyExcludesUnmappedAutoIncrementColumns() throws Exception {
        List<Map<String, Object>> targetColumns = List.of(
                target("id", "BIGINT", false, true, null),
                target("name", "VARCHAR", true, false, null),
                target("created_by", "VARCHAR", true, false, null));
        Map<Integer, String> sourceToTarget = Map.of(0, "name");
        Map<Integer, ExcelParser.CellValue> row = Map.of(0, new ExcelParser.CellValue("Ada", "string"));
        List<String> calls = new ArrayList<>();
        PreparedStatement statement = recordingStatement(calls);

        bindRow(statement, row, targetColumns, sourceToTarget, "NULL");

        assertEquals(List.of("setString:1:Ada", "setNull:2"), calls);
    }

    @Test
    void mappedInsertUsesTrustedQualifiedDialectTableName() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, plugin());
        ConnectInfo connectInfo = connectInfo(7L, "trusted_db", "trusted_schema");
        connectInfo.setDbType(DB_TYPE);
        Chat2DBContext.putContext(connectInfo);
        List<Map<String, Object>> targetColumns = List.of(target("amount", "DOUBLE", true, false, null));

        String sql = buildInsertSql("trusted_db", "trusted_schema", "orders", targetColumns,
                Map.of(0, "amount"), "DEFAULT");

        assertEquals("INSERT INTO [trusted_db].[trusted_schema].[orders] ([amount]) VALUES (?)", sql);
    }

    @Test
    void valueBindingSanitizesFormulaTextAndParsesFloatingPointDecimals() throws Exception {
        List<Map<String, Object>> targetColumns = List.of(
                target("rate", "DOUBLE", true, false, null),
                target("ratio", "FLOAT", true, false, null),
                target("formula", "VARCHAR", true, false, null));
        Map<Integer, String> sourceToTarget = Map.of(0, "rate", 1, "ratio", 2, "formula");
        Map<Integer, ExcelParser.CellValue> row = Map.of(
                0, new ExcelParser.CellValue("1.25", "string"),
                1, new ExcelParser.CellValue("2.5", "string"),
                2, new ExcelParser.CellValue("=1+1", "string"));
        List<String> calls = new ArrayList<>();
        PreparedStatement statement = recordingStatement(calls);

        bindRow(statement, row, targetColumns, sourceToTarget, "DEFAULT");

        assertEquals(List.of("setDouble:1:1.25", "setDouble:2:2.5", "setString:3:'=1+1"), calls);
    }

    @Test
    void emptyRowsAreSkippedButRowsWithDataAreNot() throws Exception {
        assertTrue(isEmptyRow(Map.of()));
        assertTrue(isEmptyRow(Map.of(0, new ExcelParser.CellValue("", "empty"))));
        assertFalse(isEmptyRow(Map.of(0, new ExcelParser.CellValue("Ada", "string"))));
    }

    @Test
    void importTargetUsesTrustedContextMetadata() {
        Chat2DBContext.putContext(connectInfo(7L, "trusted_db", "trusted_schema"));

        DbImportPreviewServiceImpl.ImportTarget target = DbImportPreviewServiceImpl
                .resolveImportTarget(7L, " trusted_db ", " trusted_schema ", "orders");

        assertEquals("trusted_db", target.databaseName());
        assertEquals("trusted_schema", target.schemaName());
        assertEquals("orders", target.tableName());
    }

    @Test
    void importTargetRejectsRequestDatabaseMismatch() {
        Chat2DBContext.putContext(connectInfo(7L, "trusted_db", "trusted_schema"));

        assertThrows(BusinessException.class,
                () -> DbImportPreviewServiceImpl.resolveImportTarget(7L, "other_db", "trusted_schema", "orders"));
    }

    @Test
    void importTargetRejectsRequestSchemaMismatch() {
        Chat2DBContext.putContext(connectInfo(7L, "trusted_db", "trusted_schema"));

        assertThrows(BusinessException.class,
                () -> DbImportPreviewServiceImpl.resolveImportTarget(7L, "trusted_db", "other_schema", "orders"));
    }

    @Test
    void importTargetRejectsDataSourceMismatch() {
        Chat2DBContext.putContext(connectInfo(7L, "trusted_db", "trusted_schema"));

        assertThrows(BusinessException.class,
                () -> DbImportPreviewServiceImpl.resolveImportTarget(8L, "trusted_db", "trusted_schema", "orders"));
    }

    @Test
    void importTargetRejectsMetadataWildcards() {
        Chat2DBContext.putContext(connectInfo(7L, "trusted_db", "trusted_schema"));

        assertThrows(BusinessException.class,
                () -> DbImportPreviewServiceImpl.resolveImportTarget(7L, "trusted%", "trusted_schema", "orders"));
        assertThrows(BusinessException.class,
                () -> DbImportPreviewServiceImpl.resolveImportTarget(7L, "trusted_db", "trusted_schema", "orders*"));
    }

    @Test
    void importTargetRejectsWildcardFromTrustedContext() {
        Chat2DBContext.putContext(connectInfo(7L, "trusted%", "trusted_schema"));

        assertThrows(BusinessException.class,
                () -> DbImportPreviewServiceImpl.resolveImportTarget(7L, "trusted%", "trusted_schema", "orders"));
    }

    private Path importFile(String name) throws Exception {
        return tempDir.resolve(name);
    }

    private static Object parseRows(Path file, int limit, Map<String, Object> csvOptions) throws Exception {
        Method method = DbImportPreviewServiceImpl.class
                .getDeclaredMethod("parseRows", java.io.File.class, int.class, Map.class);
        method.setAccessible(true);
        return invoke(method, null, file.toFile(), limit, csvOptions);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<Integer, ExcelParser.CellValue>> rows(Object outcome) throws Exception {
        Method method = outcome.getClass().getDeclaredMethod("rows");
        method.setAccessible(true);
        return (List<Map<Integer, ExcelParser.CellValue>>) method.invoke(outcome);
    }

    private static void bindRow(PreparedStatement statement, Map<Integer, ExcelParser.CellValue> row,
            List<Map<String, Object>> targetColumns, Map<Integer, String> sourceToTarget, String strategy)
            throws Exception {
        Method method = DbImportPreviewServiceImpl.class.getDeclaredMethod("bindRow", PreparedStatement.class,
                Map.class, List.class, Map.class, String.class);
        method.setAccessible(true);
        invoke(method, null, statement, row, targetColumns, sourceToTarget, strategy);
    }

    private static boolean isEmptyRow(Map<Integer, ExcelParser.CellValue> row) throws Exception {
        Method method = DbImportPreviewServiceImpl.class.getDeclaredMethod("isEmptyRow", Map.class);
        method.setAccessible(true);
        return invoke(method, null, row);
    }

    private static String buildInsertSql(String databaseName, String schemaName, String tableName,
            List<Map<String, Object>> targetColumns, Map<Integer, String> sourceToTarget, String strategy)
            throws Exception {
        Class<?> targetMetadataClass = Class.forName(
                "ai.chat2db.community.domain.core.impl.db.DbImportPreviewServiceImpl$TargetMetadata");
        Constructor<?> constructor = targetMetadataClass
                .getDeclaredConstructor(String.class, String.class, String.class, List.class);
        constructor.setAccessible(true);
        Object targetMetadata = constructor.newInstance(databaseName, schemaName, tableName, targetColumns);
        Method method = DbImportPreviewServiceImpl.class.getDeclaredMethod("buildInsertSql", targetMetadataClass,
                List.class, Map.class, String.class);
        method.setAccessible(true);
        return invoke(method, null, targetMetadata, targetColumns, sourceToTarget, strategy);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return (T) method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static Map<String, Object> target(String name, String dataType, boolean nullable,
            boolean autoIncrement, String defaultValue) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name);
        column.put("dataType", dataType);
        column.put("nullable", nullable);
        column.put("autoIncrement", autoIncrement);
        column.put("defaultValue", defaultValue);
        return column;
    }

    private static ConnectInfo connectInfo(Long dataSourceId, String databaseName, String schemaName) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setDatabaseName(databaseName);
        connectInfo.setSchemaName(schemaName);
        connectInfo.setDriverConfig(new DriverConfig());
        return connectInfo;
    }

    private static PreparedStatement recordingStatement(List<String> calls) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setString" -> calls.add("setString:" + args[0] + ":" + args[1]);
                        case "setNull" -> calls.add("setNull:" + args[0]);
                        case "setObject" -> calls.add("setObject:" + args[0] + ":" + args[1]);
                        case "setDouble" -> calls.add("setDouble:" + args[0] + ":" + args[1]);
                        case "setBigDecimal" -> calls.add("setBigDecimal:" + args[0] + ":"
                                + ((BigDecimal) args[1]).toPlainString());
                        default -> {
                        }
                    }
                    return null;
                });
    }

    private static IPlugin plugin() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metaData = new DefaultMetaService() {
            @Override
            public String getMetaDataName(String... names) {
                return java.util.Arrays.stream(names)
                        .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
                        .map(name -> "[" + name.replace("]", "]]") + "]")
                        .collect(java.util.stream.Collectors.joining("."));
            }

            @Override
            public String getQualifiedTableName(String databaseName, String schemaName, String tableName) {
                return getMetaDataName(databaseName, schemaName, tableName);
            }
        };
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
}
