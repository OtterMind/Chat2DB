package ai.chat2db.community.test.spi.model;

import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JDBCDataValueLargeCellTest {

    @Test
    void classifiesTwentyMbTextSummaryAsLargeTextCell() {
        JDBCDataValue value = new JDBCDataValue(resultSet(), metaData("LONGTEXT", Types.LONGVARCHAR), 1, true);

        ResultCell cell = value.buildResultCell("[LONGTEXT] 20.00 MB");

        assertTrue(cell.isLargeValue());
        assertTrue(cell.isTruncated());
        assertEquals("TEXT", cell.getValueType());
        assertEquals(20L * 1024L * 1024L, cell.getSizeBytes());
        assertEquals("[LONGTEXT] 20.00 MB", cell.getValue());
    }

    @Test
    void classifiesTwentyMbBinarySummaryAsLargeBinaryCell() {
        JDBCDataValue value = new JDBCDataValue(resultSet(), metaData("BLOB", Types.BLOB), 1, true);

        ResultCell cell = value.buildResultCell("[BLOB] 20.00 MB");

        assertTrue(cell.isLargeValue());
        assertEquals("BINARY", cell.getValueType());
        assertEquals(20L * 1024L * 1024L, cell.getSizeBytes());
    }

    @Test
    void classifiesImageSummaryWithDimensionsAsLargeImageCell() {
        JDBCDataValue value = new JDBCDataValue(resultSet(), metaData("IMAGE", Types.LONGVARBINARY), 1, true);

        ResultCell cell = value.buildResultCell("[IMAGE] 1024x768 JPEG image  20.00 MB");

        assertTrue(cell.isLargeValue());
        assertEquals("IMAGE", cell.getValueType());
        assertEquals(20L * 1024L * 1024L, cell.getSizeBytes());
    }

    @Test
    void leavesSmallStringsEditableInlineValues() {
        JDBCDataValue value = new JDBCDataValue(resultSet(), metaData("VARCHAR", Types.VARCHAR), 1, true);

        ResultCell cell = value.buildResultCell("small value");

        assertFalse(cell.isLargeValue());
        assertFalse(cell.isTruncated());
        assertEquals("TEXT", cell.getValueType());
        assertEquals("small value", cell.getValue());
    }

    @Test
    void truncatesJsonValuesLargerThanTenKbForLazyLoading() {
        String json = "{\"payload\":\"" + "x".repeat(11 * 1024) + "\"}";
        JDBCDataValue value = new JDBCDataValue(resultSet(), metaData("jsonb", Types.OTHER), 1, true);

        ResultCell cell = value.buildResultCell(json);

        assertTrue(cell.isLargeValue());
        assertTrue(cell.isTruncated());
        assertEquals("JSON", cell.getValueType());
        assertEquals(200, cell.getValue().length());
        assertEquals(json, cell.getRawValue());
        assertEquals((long) json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, cell.getSizeBytes());
        assertEquals((long) json.length(), cell.getSizeChars());
        assertEquals(200L, cell.getLoadedBytes());
        assertEquals(200L, cell.getLoadedChars());
    }

    @Test
    void nullClobReturnsNullInsteadOfDereferencingClob() {
        ResultSet resultSet = resultSet("getClob", null, "getString", null);
        JDBCDataValue value = new JDBCDataValue(resultSet, metaData("CLOB", Types.CLOB), 1, true);

        assertNull(value.getClobString());
    }

    @Test
    void nullClobFallsBackToDriverStringValueWhenAvailable() {
        ResultSet resultSet = resultSet("getClob", null, "getString", "dm text");
        JDBCDataValue value = new JDBCDataValue(resultSet, metaData("CLOB", Types.CLOB), 1, true);

        assertEquals("dm text", value.getClobString());
    }

    @Test
    void nullBlobReturnsNullInsteadOfDereferencingBlob() {
        ResultSet resultSet = resultSet("getBlob", null);
        JDBCDataValue value = new JDBCDataValue(resultSet, metaData("BLOB", Types.BLOB), 1, true);

        assertNull(value.getBlobString());
    }

    private static ResultSet resultSet() {
        return proxy(ResultSet.class, (proxy, method, args) -> null);
    }

    private static ResultSet resultSet(Object... methodResults) {
        return proxy(ResultSet.class, (proxy, method, args) -> {
            for (int i = 0; i < methodResults.length; i += 2) {
                if (method.getName().equals(methodResults[i])) {
                    return methodResults[i + 1];
                }
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static ResultSetMetaData metaData(String columnTypeName, int sqlType) {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnTypeName" -> columnTypeName;
            case "getColumnType" -> sqlType;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(target, method, args);
            }
            return handler.invoke(target, method, args);
        });
        return type.cast(proxy);
    }

    private static Object invokeObjectMethod(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> target.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
