package ai.chat2db.community.test.spi.model;

import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertEquals((long) json.getBytes(StandardCharsets.UTF_8).length, cell.getSizeBytes());
        assertEquals((long) json.length(), cell.getSizeChars());
        assertEquals(200L, cell.getLoadedBytes());
        assertEquals(200L, cell.getLoadedChars());
    }

    @Test
    void keepsTheTextItReadSoTheCaptureDoesNotReadTheValueAgain() {
        // The summary starts above one million characters, which is where the driver stream is consumed
        String text = "长".repeat(1_100_000);
        AtomicInteger streamOpens = new AtomicInteger();
        JDBCDataValue value = new JDBCDataValue(characterStreamResultSet(text, streamOpens),
                metaData("LONGTEXT", Types.LONGVARCHAR), 1, true);

        String display = value.getCharsetString();
        assertTrue(display.startsWith("[LONGTEXT]"), "a big value is shown as a summary: " + display);

        ResultCell cell = value.buildResultCell(display);

        assertEquals(text, cell.getRawValue(), "the capture must reuse the characters that were already read");
        // The summary size is the size the driver reported, so the capture corrects it from the value it kept
        assertTrue(cell.getSizeChars() > 0L, "a summary still reports a size: " + cell.getSizeChars());
        assertEquals(1, streamOpens.get(), "the value must not be read a second time");
    }

    @Test
    void fallsBackToTheDriverHandleWhenTheTextIsTooLargeToKeep() {
        String text = "长".repeat(17 * 1024 * 1024);
        AtomicInteger streamOpens = new AtomicInteger();
        Clob handle = proxy(Clob.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        JDBCDataValue value = new JDBCDataValue(
                characterStreamResultSet(text, streamOpens, handle), metaData("LONGTEXT", Types.LONGVARCHAR), 1, true);

        String display = value.getCharsetString();
        ResultCell cell = value.buildResultCell(display);

        org.junit.jupiter.api.Assertions.assertSame(handle, cell.getRawValue(),
                "a value that is too large to keep stays on its driver handle");
        assertEquals(1, streamOpens.get(), "the stream is still only read once");
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

    private static ResultSet characterStreamResultSet(String text, AtomicInteger streamOpens) {
        return characterStreamResultSet(text, streamOpens, null);
    }

    private static ResultSet characterStreamResultSet(String text, AtomicInteger streamOpens, Clob handle) {
        return proxy(ResultSet.class, (proxy, method, args) -> {
            if ("getCharacterStream".equals(method.getName())) {
                streamOpens.incrementAndGet();
                return new StringReader(text);
            }
            if ("getClob".equals(method.getName())) {
                return handle;
            }
            return defaultValue(method.getReturnType());
        });
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
