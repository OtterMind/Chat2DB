package ai.chat2db.spi.model.value;

import ai.chat2db.spi.DefaultValueProcessor;
import java.io.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedJdbcValueReaderTest {
    @Test
    void neverMaterializesAnUnboundedClobOrRetainsItsJdbcLocator() {
        AtomicInteger read = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        Reader source = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, 'x');
                read.addAndGet(length);
                return length;
            }
            @Override public void close() { closed.set(true); }
        };
        var value = jdbc(Types.CLOB, "CLOB", (method, args) -> {
            if (method.equals("getCharacterStream")) return source;
            throw new AssertionError("Unexpected JDBC materialization: " + method);
        });
        var cell = value.buildBoundedResultCell(new ResultValueBudget(1024), new DefaultValueProcessor());
        assertEquals("x".repeat(1024), cell.getValue());
        assertTrue(cell.isTruncated());
        assertSame(cell.getValue(), cell.getRawValue());
        assertNull(cell.getSizeChars());
        assertTrue(read.get() <= 8192);
        assertTrue(closed.get());
    }

    @Test
    void binaryCaptureUsesAStreamAndAccountsForHexExpansion() {
        AtomicInteger read = new AtomicInteger();
        InputStream source = new InputStream() {
            @Override public int read() { read.incrementAndGet(); return 255; }
            @Override public int read(byte[] bytes, int offset, int length) {
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 255);
                read.addAndGet(length);
                return length;
            }
        };
        var value = jdbc(Types.BLOB, "BLOB", (method, args) -> {
            if (method.equals("getBinaryStream")) return source;
            throw new AssertionError("Unexpected binary materialization: " + method);
        });
        var cell = value.buildBoundedResultCell(new ResultValueBudget(1024), new DefaultValueProcessor());
        assertEquals("0x" + "FF".repeat(511), cell.getValue());
        assertEquals(1024L, cell.getLoadedBytes());
        assertTrue(cell.isTruncated());
        assertTrue(read.get() <= 8192);
    }

    @Test
    void oneBudgetSpansCellsWithoutSplittingUnicodeAndPreservesSqlNull() {
        var budget = new ResultValueBudget(7);
        var one = jdbc(Types.VARCHAR, "VARCHAR", (method, args) -> {
            if (method.equals("getCharacterStream")) return new StringReader("😀😀");
            throw new AssertionError(method);
        }).buildBoundedResultCell(budget, new DefaultValueProcessor());
        assertEquals("😀", one.getValue());
        assertTrue(one.isTruncated());
        var two = jdbc(Types.VARCHAR, "VARCHAR", (method, args) -> {
            if (method.equals("getCharacterStream")) return new StringReader("文");
            throw new AssertionError(method);
        }).buildBoundedResultCell(budget, new DefaultValueProcessor());
        assertEquals("文", two.getValue());
        assertFalse(two.isTruncated());
        assertEquals(0, budget.remaining());
        var empty = jdbc(Types.VARCHAR, "VARCHAR", (method, args) -> {
            if (method.equals("getCharacterStream")) return null;
            throw new AssertionError(method);
        }).buildBoundedResultCell(budget, new DefaultValueProcessor());
        assertNull(empty.getValue());
        assertFalse(empty.isTruncated());
    }

    private JDBCDataValue jdbc(int type, String name, Call call) {
        var metadata = (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnType" -> type;
                    case "getColumnTypeName" -> name;
                    default -> throw new AssertionError(method.getName());
                });
        var result = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> call.invoke(method.getName(), args));
        return new JDBCDataValue(result, metadata, 1, false);
    }
    @FunctionalInterface private interface Call { Object invoke(String method, Object[] arguments) throws Exception; }
}
