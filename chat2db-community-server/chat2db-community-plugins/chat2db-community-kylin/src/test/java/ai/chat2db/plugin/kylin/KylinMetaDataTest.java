package ai.chat2db.plugin.kylin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression test for plugin:kylin-1: KylinMetaData must override tableDDL so that
 * Show Create Table / export / AI DDL tool do not crash with UnsupportedOperationException.
 */
class KylinMetaDataTest {

    @Test
    void tableDdlQuotesIdentifiersAndEscapesCommentLiterals() {
        Connection connection = connectionWithMetadata(
                List.of(
                        List.<Object>of("display name", "VARCHAR", Types.VARCHAR, 64, 0, 1, "employee's id"),
                        List.<Object>of("a\"b", "DECIMAL", Types.DECIMAL, 10, 2, 0, "")),
                List.of(
                        List.<Object>of("select", "display name", false, (short) 1),
                        List.<Object>of("select", "a\"b", false, (short) 2)));

        String ddl = new KylinMetaData().tableDDL(connection, "DEFAULT", "DEFAULT", "order");

        assertEquals("CREATE TABLE \"order\" (\n"
                + "\t\"display name\" VARCHAR(64) COMMENT 'employee''s id',\n"
                + "\t\"a\"\"b\" DECIMAL(10,2) NOT NULL\n"
                + ");\n"
                + "CREATE UNIQUE INDEX \"select\" ON \"order\" (\"display name\", \"a\"\"b\");", ddl);
    }

    @Test
    void tableDdlUsesJdbcTypeInsteadOfHostileTypeName() {
        Connection connection = connectionWithMetadata(
                List.of(List.<Object>of("id", "INTEGER); DROP TABLE users; --", Types.INTEGER, 10, 0, 0, "")),
                List.of());

        String ddl = new KylinMetaData().tableDDL(connection, "DEFAULT", "", "orders");

        assertEquals("CREATE TABLE \"orders\" (\n\t\"id\" INTEGER NOT NULL\n);", ddl);
    }

    @Test
    void tableDdlRejectsUnsupportedJdbcType() {
        Connection connection = connectionWithMetadata(
                List.of(List.<Object>of("payload", "OTHER", Types.OTHER, 0, 0, 1, "")),
                List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new KylinMetaData().tableDDL(connection, "DEFAULT", "DEFAULT", "orders"));

        assertEquals("Unsupported Kylin JDBC column type: " + Types.OTHER, exception.getMessage());
    }

    @Test
    void tableDdlSupportsKylinAnyAndArrayTypes() {
        Connection connection = connectionWithMetadata(
                List.of(
                        List.<Object>of("attributes", "VARCHAR(256) CHARACTER SET \"ISO-8859-1\" "
                                + "COLLATE \"ISO-8859-1$en_US\" NOT NULL ARRAY NOT NULL",
                                Types.ARRAY, -1, 0, 1, ""),
                        List.<Object>of("any_value", "ANY NOT NULL ARRAY", Types.ARRAY, -1, 0, 1, ""),
                        List.<Object>of("any_precision", "ANY(8) NOT NULL ARRAY", Types.ARRAY, -1, 0, 1, ""),
                        List.<Object>of("any_precision_scale", "ANY(8, 2) NOT NULL ARRAY",
                                Types.ARRAY, -1, 0, 1, ""),
                        List.<Object>of("raw", "ANY", Types.JAVA_OBJECT, -1, 0, 1, "")),
                List.of());

        String ddl = new KylinMetaData().tableDDL(connection, "DEFAULT", "DEFAULT", "events");

        assertEquals("CREATE TABLE \"events\" (\n"
                + "\t\"attributes\" ARRAY<VARCHAR(256)>,\n"
                + "\t\"any_value\" ARRAY<ANY>,\n"
                + "\t\"any_precision\" ARRAY<ANY(8)>,\n"
                + "\t\"any_precision_scale\" ARRAY<ANY(8,2)>,\n"
                + "\t\"raw\" ANY\n"
                + ");", ddl);
    }

    @Test
    void tableDdlRejectsHostileArrayTypeName() {
        Connection connection = connectionWithMetadata(
                List.of(List.<Object>of("payload", "VARCHAR(256) NOT NULL ARRAY); DROP TABLE users; --",
                        Types.ARRAY, -1, 0, 1, "")),
                List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new KylinMetaData().tableDDL(connection, "DEFAULT", "DEFAULT", "events"));

        assertEquals("Unsupported Kylin JDBC ARRAY type for column: payload", exception.getMessage());
    }

    @Test
    void tableDdlRejectsMalformedArrayTypeNames() {
        List<String> typeNames = List.of(
                "VARCHAR(256) CHARACTER SET \"ISO-8859-1\" NOT NULL ARRAY",
                "VARCHAR(256) COLLATE \"ISO-8859-1$en_US\" NOT NULL ARRAY",
                "VARCHAR(256) CHARACTER SET \"ISO-8859-1\" COLLATE NOT NULL ARRAY",
                "VARCHAR(256) NOT NULL ARRAY NOT NULL ARRAY",
                "VARCHAR(256) NOT NULL ARRAY trailing",
                "VARCHAR(256) NOT NULL ARRAY\n",
                "VARCHAR(256)\r NOT NULL ARRAY");

        for (String typeName : typeNames) {
            Connection connection = connectionWithMetadata(
                    List.of(List.<Object>of("payload", typeName, Types.ARRAY, -1, 0, 1, "")),
                    List.of());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> new KylinMetaData().tableDDL(connection, "DEFAULT", "DEFAULT", "events"), typeName);

            assertEquals("Unsupported Kylin JDBC ARRAY type for column: payload", exception.getMessage(), typeName);
        }
    }

    @Test
    void tableDdlNormalizesEmptyCatalogAndSchemaForMetadataCalls() {
        MetadataCallArguments callArguments = new MetadataCallArguments();
        Connection connection = connectionWithMetadata(
                List.of(List.<Object>of("id", "BIGINT", Types.BIGINT, 19, 0, 1, "")),
                List.of(), callArguments);

        new KylinMetaData().tableDDL(connection, "", "", "orders");

        assertNull(callArguments.columns[0]);
        assertNull(callArguments.columns[1]);
        assertEquals("orders", callArguments.columns[2]);
        assertNull(callArguments.columns[3]);
        assertNull(callArguments.indexes[0]);
        assertNull(callArguments.indexes[1]);
        assertEquals("orders", callArguments.indexes[2]);
    }

    @Test
    void tableDdlReturnsEmptyStringWhenMetadataHasNoColumns() {
        MetadataCallArguments callArguments = new MetadataCallArguments();
        Connection connection = connectionWithMetadata(List.of(), List.of(), callArguments);

        String ddl = new KylinMetaData().tableDDL(connection, "DEFAULT", "DEFAULT", "missing_table");

        assertEquals("", ddl);
        assertNull(callArguments.indexes);
    }

    private static Connection connectionWithMetadata(List<List<Object>> columnRows, List<List<Object>> indexRows) {
        return connectionWithMetadata(columnRows, indexRows, new MetadataCallArguments());
    }

    private static Connection connectionWithMetadata(List<List<Object>> columnRows, List<List<Object>> indexRows,
                                                     MetadataCallArguments callArguments) {
        ResultSet columnsRs = resultSet(
                List.of("COLUMN_NAME", "TYPE_NAME", "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS", "NULLABLE",
                        "REMARKS"),
                columnRows);
        ResultSet indexRs = resultSet(
                List.of("INDEX_NAME", "COLUMN_NAME", "NON_UNIQUE", "ORDINAL_POSITION"), indexRows);
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (p, method, args) -> {
            if ("getColumns".equals(method.getName())) {
                callArguments.columns = args.clone();
                return columnsRs;
            }
            if ("getIndexInfo".equals(method.getName())) {
                callArguments.indexes = args.clone();
                return indexRs;
            }
            return defaultValue(method.getReturnType());
        });
        return proxy(Connection.class, (p, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                return metaData;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static final class MetadataCallArguments {
        private Object[] columns;
        private Object[] indexes;
    }

    private static ResultSet resultSet(List<String> labels, List<List<Object>> rows) {
        AtomicInteger cursor = new AtomicInteger(-1);
        ResultSetMetaData rsMetaData = proxy(ResultSetMetaData.class, (p, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> labels.size();
            case "getColumnLabel", "getColumnName" -> labels.get((Integer) args[0] - 1);
            default -> defaultValue(method.getReturnType());
        });
        return proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
            case "getMetaData" -> rsMetaData;
            case "next" -> cursor.incrementAndGet() < rows.size();
            case "getObject" -> rows.get(cursor.get()).get((Integer) args[0] - 1);
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
