package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlMetaDataColumnTypeTest {

    @Test
    void extractsArgumentsThroughTheOuterClosingParenthesis() {
        assertEquals("'a(b)','c)'", MysqlMetaData.extractColumnTypeArguments("enum('a(b)','c)')"));
        assertEquals("10,2", MysqlMetaData.extractColumnTypeArguments("decimal(10,2) unsigned"));
        assertNull(MysqlMetaData.extractColumnTypeArguments("varchar"));
    }

    @Test
    void mapsOrdinaryColumnWhenExtraMetadataIsNull() {
        MysqlMetaData metaData = new MysqlMetaData();
        Connection connection = connectionWithColumnRow(Map.of(
                "COLUMN_NAME", "name",
                "DATA_TYPE", "varchar",
                "COLUMN_TYPE", "varchar(64)",
                "IS_NULLABLE", "YES",
                "COLUMN_KEY", "",
                "COLUMN_COMMENT", "",
                "CHARACTER_SET_NAME", "utf8mb4",
                "COLLATION_NAME", "utf8mb4_general_ci"
        ));

        List<TableColumn> columns = assertDoesNotThrow(() -> metaData.columns(connection, "app", null, "users"));

        assertEquals(1, columns.size());
        TableColumn column = columns.get(0);
        assertEquals("name", column.getName());
        assertEquals("VARCHAR", column.getColumnType());
        assertFalse(column.getAutoIncrement());
        assertFalse(column.getOnUpdateCurrentTimestamp());
        assertTrue(column.getVisible());
    }

    @Test
    void mapsInvisibleColumnFromExtraMetadata() {
        MysqlMetaData metaData = new MysqlMetaData();
        Connection connection = connectionWithColumnRow(Map.of(
                "COLUMN_NAME", "name",
                "DATA_TYPE", "varchar",
                "COLUMN_TYPE", "varchar(64)",
                "IS_NULLABLE", "YES",
                "COLUMN_KEY", "",
                "EXTRA", "INVISIBLE",
                "COLUMN_COMMENT", "",
                "CHARACTER_SET_NAME", "utf8mb4",
                "COLLATION_NAME", "utf8mb4_general_ci"
        ));

        List<TableColumn> columns = metaData.columns(connection, "app", null, "users");

        assertEquals(1, columns.size());
        assertFalse(columns.get(0).getVisible());
    }

    private static Connection connectionWithColumnRow(Map<String, String> row) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(MysqlMetaDataColumnTypeTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, new SingleRowResultSet(row));
        PreparedStatement preparedStatement = (PreparedStatement) Proxy.newProxyInstance(
                MysqlMetaDataColumnTypeTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(MysqlMetaDataColumnTypeTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        return preparedStatement;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static class SingleRowResultSet implements java.lang.reflect.InvocationHandler {
        private final Map<String, String> row;
        private boolean read;

        private SingleRowResultSet(Map<String, String> row) {
            this.row = row;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    boolean hasNext = !read;
                    read = true;
                    yield hasNext;
                }
                case "getString" -> row.get(args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }
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
