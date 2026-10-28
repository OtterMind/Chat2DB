package ai.chat2db.plugin.sqlserver;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerColumnTypeEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the NCHAR/NVARCHAR size mapping in {@link SqlServerMetaData#columns}.
 * JDBC reports MAX columns with COLUMN_SIZE = -1 and
 * {@link SqlServerColumnTypeEnum} renders the -1 sentinel as {@code (MAX)},
 * so columns() must keep -1 instead of normalizing it to Integer.MAX_VALUE.
 */
class SqlServerMetaDataColumnSizeTest {

    @Test
    void shouldMapUnicodeColumnSizesWithoutLosingMaxSentinel() {
        List<TableColumn> columns = new SqlServerMetaData().columns(
                columnsConnection(), "catalog", "dbo", "orders");

        assertEquals(6, columns.size());
        assertNull(columns.get(0).getColumnSize());
        assertEquals(-1, columns.get(1).getColumnSize());
        assertEquals(100, columns.get(2).getColumnSize());
        assertEquals(10, columns.get(3).getColumnSize());
        assertEquals(1, columns.get(4).getColumnSize());
        assertNull(columns.get(5).getColumnSize());
    }

    @Test
    void nvarcharMaxSentinelRoundTripsIntoMaxKeyword() {
        TableColumn column = new TableColumn();
        column.setName("payload");
        column.setColumnType("NVARCHAR");
        column.setColumnSize(-1);

        String sql = SqlServerColumnTypeEnum.NVARCHAR.buildCreateColumnSql(column);

        assertTrue(sql.contains("NVARCHAR(MAX)"), sql);
    }

    private static Connection columnsConnection() {
        ResultSet resultSet = columnsResultSet();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                SqlServerMetaDataColumnSizeTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                SqlServerMetaDataColumnSizeTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName())
                        ? statement : defaultValue(method.getReturnType()));
    }

    private static ResultSet columnsResultSet() {
        String[] names = {
                "nchar_max", "nvarchar_max", "nchar_100", "nvarchar_10", "nchar_1", "unknown_size"
        };
        String[] types = {
                "nchar", "nvarchar", "nchar", "nvarchar", "nchar", "nvarchar"
        };
        Integer[] sizes = {-1, -1, 200, 20, 2, null};
        int[] row = {-1};
        boolean[] wasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(
                SqlServerMetaDataColumnSizeTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++row[0] < names.length;
                    case "getString" -> switch ((String) args[0]) {
                        case "COLUMN_NAME" -> names[row[0]];
                        case "DATA_TYPE" -> types[row[0]];
                        default -> null;
                    };
                    case "getInt" -> {
                        Integer value = switch ((String) args[0]) {
                            case "COLUMN_SIZE" -> sizes[row[0]];
                            case "ORDINAL_POSITION" -> row[0] + 1;
                            default -> 0;
                        };
                        wasNull[0] = value == null;
                        yield value == null ? 0 : value;
                    }
                    case "wasNull" -> wasNull[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
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
