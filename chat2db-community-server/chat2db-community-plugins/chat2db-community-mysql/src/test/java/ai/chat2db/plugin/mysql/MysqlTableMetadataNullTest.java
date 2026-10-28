package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.metadata.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static ai.chat2db.plugin.mysql.constant.MysqlMetaDataConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MysqlTableMetadataNullTest {

    @Test
    void nullAutoIncrementRemainsNull() {
        List<Table> tables = new MysqlMetaData().tables(connection(null), "db", null, null);

        assertEquals(1, tables.size());
        assertNull(tables.get(0).getIncrementValue());
    }

    @Test
    void nonNullAutoIncrementIsPreserved() {
        List<Table> tables = new MysqlMetaData().tables(connection(42L), "db", null, null);

        assertEquals(42L, tables.get(0).getIncrementValue());
    }

    private static Connection connection(Long autoIncrement) {
        int[] query = {0};
        return (Connection) Proxy.newProxyInstance(
            MysqlTableMetadataNullTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (!"prepareStatement".equals(method.getName())) {
                    throw new AssertionError("Unexpected Connection call: " + method.getName());
                }
                ResultSet resultSet = query[0]++ == 0 ? emptyResultSet() : tableResultSet(autoIncrement);
                return statement(resultSet);
            });
    }

    private static PreparedStatement statement(ResultSet resultSet) {
        return (PreparedStatement) Proxy.newProxyInstance(
            MysqlTableMetadataNullTest.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> true;
                case "getResultSet" -> resultSet;
                case "close" -> null;
                default -> throw new AssertionError("Unexpected PreparedStatement call: " + method.getName());
            });
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
            MysqlTableMetadataNullTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> false;
                case "close" -> null;
                default -> throw new AssertionError("Unexpected collation ResultSet call: " + method.getName());
            });
    }

    private static ResultSet tableResultSet(Long autoIncrement) {
        int[] row = {-1};
        boolean[] lastWasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(
            MysqlTableMetadataNullTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++row[0] == 0;
                case "getString" -> {
                    String value = stringValue((String) args[0]);
                    lastWasNull[0] = value == null;
                    yield value;
                }
                case "getLong" -> {
                    Long value = longValue((String) args[0], autoIncrement);
                    lastWasNull[0] = value == null;
                    yield value == null ? 0L : value;
                }
                case "wasNull" -> lastWasNull[0];
                case "close" -> null;
                default -> throw new AssertionError("Unexpected table ResultSet call: " + method.getName());
            });
    }

    private static String stringValue(String label) {
        return switch (label) {
            case FIELD_TABLE_NAME -> "items";
            case FIELD_ENGINE_UPPER -> "InnoDB";
            case FIELD_CREATE_TIME -> "2026-08-31 00:00:00";
            case FIELD_UPDATE_TIME -> null;
            case FIELD_TABLE_COLLATION -> null;
            case FIELD_TABLE_COMMENT -> "items table";
            default -> throw new AssertionError("Unexpected string label: " + label);
        };
    }

    private static Long longValue(String label, Long autoIncrement) {
        return switch (label) {
            case FIELD_TABLE_ROWS -> 12L;
            case FIELD_DATA_LENGTH -> 128L;
            case FIELD_AUTO_INCREMENT -> autoIncrement;
            default -> throw new AssertionError("Unexpected long label: " + label);
        };
    }
}
