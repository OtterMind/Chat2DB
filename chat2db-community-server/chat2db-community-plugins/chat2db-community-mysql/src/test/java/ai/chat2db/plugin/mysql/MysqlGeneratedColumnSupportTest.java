package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableMeta;
import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.plugin.mysql.enums.type.MysqlColumnTypeEnum;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlGeneratedColumnSupportTest {

    @AfterEach
    void clearContext() {
        Chat2DBContext.removeContext();
    }

    @Test
    void generatedColumnSqlRequiresMysql576OrNewer() {
        TableColumn column = generatedColumn();

        withMysqlVersion("5.6.51");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));

        withMysqlVersion("5.7.5");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));

        withMysqlVersion("5.7.44");
        String mysql57 = MysqlColumnTypeEnum.INT.buildCreateColumnSql(column);
        assertTrue(mysql57.contains("GENERATED ALWAYS AS (`price` * 2) VIRTUAL"), mysql57);

        withMysqlVersion("8.0.36");
        String mysql80 = MysqlColumnTypeEnum.INT.buildCreateColumnSql(column);
        assertTrue(mysql80.contains("GENERATED ALWAYS AS (`price` * 2) VIRTUAL"), mysql80);
    }

    @Test
    void generatedColumnStorageTypeIsWhitelistedAndCanonicalized() {
        TableColumn column = generatedColumn();

        withMysqlVersion("8.0.36");
        column.setGeneratedColumnType("stored");
        assertTrue(MysqlColumnTypeEnum.INT.buildCreateColumnSql(column)
                .contains("GENERATED ALWAYS AS (`price` * 2) STORED"));

        column.setGeneratedColumnType("PERSISTED");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void generatedColumnExpressionCannotBreakOutOfExpressionParentheses() {
        TableColumn column = generatedColumn();
        column.setGenerationExpression("`price`) STORED, `injected` INT");

        withMysqlVersion("8.0.36");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void generatedColumnExpressionRejectsUnsafeStatementKeywordsOutsideQuotes() {
        TableColumn column = generatedColumn();

        withMysqlVersion("8.0.36");
        column.setGenerationExpression("concat(`drop`, 'table')");
        assertTrue(MysqlColumnTypeEnum.INT.buildCreateColumnSql(column)
                .contains("GENERATED ALWAYS AS (concat(`drop`, 'table')) VIRTUAL"));

        column.setGenerationExpression("`price`; DROP TABLE `orders`");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void aiCreateColumnSqlIncludesGeneratedColumnSyntaxWithoutDefaultOrAutoIncrement() {
        TableColumn column = generatedColumn();
        column.setDefaultValue("0");
        column.setAutoIncrement(Boolean.TRUE);

        withMysqlVersion("8.0.36");
        String sql = MysqlColumnTypeEnum.INT.buildAICreateColumnSql(column);

        assertTrue(sql.contains("GENERATED ALWAYS AS (`price` * 2) VIRTUAL"), sql);
        assertFalse(sql.contains("DEFAULT"), sql);
        assertFalse(sql.contains("AUTO_INCREMENT"), sql);
    }

    @Test
    void generatedStringColumnPlacesCharsetAndCollationBeforeExpression() {
        TableColumn column = generatedColumn();
        column.setColumnType("VARCHAR");
        column.setColumnSize(64);
        column.setCharSetName("utf8mb4");
        column.setCollationName("utf8mb4_bin");

        withMysqlVersion("8.0.36");
        String sql = MysqlColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);

        int charset = sql.indexOf("CHARACTER SET utf8mb4");
        int collation = sql.indexOf("COLLATE utf8mb4_bin");
        int expression = sql.indexOf("GENERATED ALWAYS AS");
        assertTrue(charset >= 0 && charset < expression, sql);
        assertTrue(collation >= 0 && collation < expression, sql);
    }

    @Test
    void tableMetaExposesGeneratedColumnCapabilityFromServerVersion() {
        MysqlMetaData metaData = new MysqlMetaData();

        withMysqlVersion("5.6.51");
        TableMeta mysql56 = metaData.getTableMeta(null, null, null);
        assertEquals(Boolean.FALSE, mysql56.getGeneratedColumnSupported());
        assertEquals("5.7.6", mysql56.getGeneratedColumnMinVersion());
        assertTrue(mysql56.getGeneratedColumnUnsupportedReason().contains("5.7.6"));

        withMysqlVersion("5.7.5");
        TableMeta mysql575 = metaData.getTableMeta(null, null, null);
        assertEquals(Boolean.FALSE, mysql575.getGeneratedColumnSupported());
        assertEquals("5.7.6", mysql575.getGeneratedColumnMinVersion());
        assertTrue(mysql575.getGeneratedColumnUnsupportedReason().contains("5.7.6"));

        withMysqlVersion("5.7.44-log");
        TableMeta mysql57 = metaData.getTableMeta(null, null, null);
        assertEquals(Boolean.TRUE, mysql57.getGeneratedColumnSupported());
        assertEquals("5.7.6", mysql57.getGeneratedColumnMinVersion());
        assertEquals(null, mysql57.getGeneratedColumnUnsupportedReason());
    }

    @Test
    void metadataReadbackIncludesGenerationExpressionAndStorageType() {
        MysqlMetaData metaData = new MysqlMetaData();

        withMysqlVersion("8.0.36");
        List<TableColumn> columns = metaData.columns(connectionReturningGeneratedColumn(), new TableMetadataRequest(
                "shop", null, "products"));

        assertEquals(1, columns.size());
        TableColumn column = columns.get(0);
        assertEquals(Boolean.TRUE, column.getGeneratedColumn());
        assertEquals("`price` * 2", column.getGenerationExpression());
        assertEquals("STORED", column.getGeneratedColumnType());
    }

    @Test
    void metadataReadbackSkipsGenerationExpressionOnUnsupportedServerVersions() {
        MysqlMetaData metaData = new MysqlMetaData();

        withMysqlVersion("5.6.51");
        List<TableColumn> columns = metaData.columns(connectionReturningGeneratedColumn(), new TableMetadataRequest(
                "shop", null, "products"));

        assertEquals(1, columns.size());
        assertEquals(null, columns.get(0).getGenerationExpression());
        assertEquals(null, columns.get(0).getGeneratedColumnType());
    }

    @Test
    void metadataReadbackKeepsGeneratedFlagWhenExpressionIsHiddenByPrivileges() {
        MysqlMetaData metaData = new MysqlMetaData();

        withMysqlVersion("8.0.36");
        List<TableColumn> columns = metaData.columns(connectionReturningGeneratedColumnWithHiddenExpression(),
                new TableMetadataRequest("shop", null, "products"));

        assertEquals(1, columns.size());
        TableColumn column = columns.get(0);
        assertEquals(Boolean.TRUE, column.getGeneratedColumn());
        assertEquals(null, column.getGenerationExpression());
        assertEquals("STORED", column.getGeneratedColumnType());
    }

    @Test
    void generatedColumnStorageConversionRequiresExplicitRebuildConfirmation() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table oldTable = Table.builder()
                .databaseName("shop")
                .name("products")
                .columnList(List.of(generatedColumn()))
                .indexList(List.of())
                .build();
        TableColumn converted = generatedColumn();
        converted.setOldName("double_price");
        converted.setGeneratedColumnType("STORED");
        converted.setEditStatus(EditStatusEnum.MODIFY.name());
        Table newTable = Table.builder()
                .databaseName("shop")
                .name("products")
                .columnList(List.of(converted))
                .indexList(List.of())
                .build();

        withMysqlVersion("8.0.36");
        assertThrows(IllegalArgumentException.class, () -> builder.buildAlterTable(oldTable, newTable));

        newTable.setAllowGeneratedColumnStorageRebuild(Boolean.TRUE);
        String sql = builder.buildAlterTable(oldTable, newTable);
        assertTrue(sql.contains("DROP COLUMN `double_price`,\n"
                + "\tADD COLUMN `double_price` INT GENERATED ALWAYS AS (`price` * 2) STORED"), sql);
        assertTrue(sql.contains(" FIRST"), sql);
        assertFalse(sql.contains("MODIFY COLUMN `double_price`"), sql);
    }

    @Test
    void hiddenGeneratedExpressionBlocksUnrelatedColumnModification() {
        TableColumn hiddenGenerated = generatedColumn();
        hiddenGenerated.setGeneratedColumn(Boolean.TRUE);
        hiddenGenerated.setGenerationExpression(null);
        Table oldTable = Table.builder()
                .name("orders")
                .columnList(List.of(hiddenGenerated))
                .indexList(List.of())
                .build();

        TableColumn modified = generatedColumn();
        modified.setGeneratedColumn(Boolean.TRUE);
        modified.setOldName(modified.getName());
        modified.setGenerationExpression(null);
        modified.setComment("updated comment");
        modified.setEditStatus(EditStatusEnum.MODIFY.name());
        Table newTable = Table.builder()
                .name("orders")
                .columnList(List.of(modified))
                .indexList(List.of())
                .allowGeneratedColumnStorageRebuild(true)
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new MysqlSqlBuilder().buildAlterTable(oldTable, newTable));

        assertTrue(exception.getMessage().contains("generation expression is unavailable"));
    }

    private static TableColumn generatedColumn() {
        return TableColumn.builder()
                .name("double_price")
                .columnType("INT")
                .nullable(1)
                .generationExpression("`price` * 2")
                .generatedColumnType("VIRTUAL")
                .build();
    }

    private static void withMysqlVersion(String version) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MYSQL");
        connectInfo.setDbVersion(version);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("MYSQL");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    private static Connection connectionReturningGeneratedColumn() {
        ResultSet resultSet = resultSet(Map.ofEntries(
                Map.entry("COLUMN_NAME", "double_price"),
                Map.entry("DATA_TYPE", "int"),
                Map.entry("COLUMN_DEFAULT", ""),
                Map.entry("EXTRA", "STORED GENERATED"),
                Map.entry("COLUMN_COMMENT", ""),
                Map.entry("COLUMN_KEY", ""),
                Map.entry("IS_NULLABLE", "YES"),
                Map.entry("GENERATION_EXPRESSION", "`price` * 2"),
                Map.entry("ORDINAL_POSITION", 1),
                Map.entry("NUMERIC_SCALE", 0),
                Map.entry("CHARACTER_SET_NAME", ""),
                Map.entry("COLLATION_NAME", ""),
                Map.entry("COLUMN_TYPE", "int")));
        PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> switch (method) {
            case "execute" -> true;
            case "getResultSet" -> resultSet;
            case "close" -> null;
            default -> defaultValue(method);
        });
        return proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                return statement;
            }
            return defaultValue(method);
        });
    }

    private static Connection connectionReturningGeneratedColumnWithHiddenExpression() {
        ResultSet resultSet = resultSet(Map.ofEntries(
                Map.entry("COLUMN_NAME", "double_price"),
                Map.entry("DATA_TYPE", "int"),
                Map.entry("COLUMN_DEFAULT", ""),
                Map.entry("EXTRA", "STORED GENERATED"),
                Map.entry("COLUMN_COMMENT", ""),
                Map.entry("COLUMN_KEY", ""),
                Map.entry("IS_NULLABLE", "YES"),
                Map.entry("GENERATION_EXPRESSION", new SQLException("permission denied")),
                Map.entry("ORDINAL_POSITION", 1),
                Map.entry("NUMERIC_SCALE", 0),
                Map.entry("CHARACTER_SET_NAME", ""),
                Map.entry("COLLATION_NAME", ""),
                Map.entry("COLUMN_TYPE", "int")));
        PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> switch (method) {
            case "execute" -> true;
            case "getResultSet" -> resultSet;
            case "close" -> null;
            default -> defaultValue(method);
        });
        return proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                return statement;
            }
            return defaultValue(method);
        });
    }

    private static ResultSet resultSet(Map<String, Object> row) {
        final boolean[] next = {true};
        return proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> {
                boolean hasNext = next[0];
                next[0] = false;
                yield hasNext;
            }
            case "getString" -> {
                Object value = row.get(String.valueOf(args[0]).toUpperCase(Locale.ROOT));
                if (value instanceof Throwable throwable) {
                    throw throwable;
                }
                yield value == null ? null : String.valueOf(value);
            }
            case "getInt" -> {
                Object value = row.get(String.valueOf(args[0]).toUpperCase(Locale.ROOT));
                yield value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            }
            case "close" -> null;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }

    private static Object defaultValue(String method) {
        return switch (method) {
            case "toString" -> "MysqlGeneratedColumnSupportTest proxy";
            case "hashCode" -> 0;
            case "equals" -> false;
            default -> null;
        };
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
