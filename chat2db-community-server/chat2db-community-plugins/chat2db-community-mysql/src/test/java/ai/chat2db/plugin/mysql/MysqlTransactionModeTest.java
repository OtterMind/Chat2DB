package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MysqlTransactionModeTest {
    @Test void disabledServerModePreservesScriptOwnershipEvenWithAnUnchangedJdbcFlag() throws Exception {
        for (int depth : new int[]{0, 1}) {
            Fixture fixture = new Fixture(depth, false, false);
            var result = new MysqlMetaData().hasOpenTransaction(fixture.connection, fixture);
            if (depth == 0) assertTrue(result.orElseThrow()); else assertTrue(result.isEmpty());
            fixture.assertClosedAndRegistered();
        }
    }
    @Test void missingServerRowFailsInsteadOfGuessingNoTransaction() {
        Fixture fixture = new Fixture(0, true, false);
        assertThrows(SQLException.class, () -> new MysqlMetaData().hasOpenTransaction(fixture.connection, fixture));
        fixture.assertClosedAndRegistered();
    }
    @Test void failedProbeStillReleasesItsStatement() {
        Fixture fixture = new Fixture(1, false, true);
        assertThrows(SQLException.class, () -> new MysqlMetaData().hasOpenTransaction(fixture.connection, fixture));
        fixture.assertClosedAndRegistered();
    }
    private static final class Fixture implements ISqlExecutionStatementListener {
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger created = new AtomicInteger(), released = new AtomicInteger();
        final Connection connection;
        Fixture(int depth, boolean noRows, boolean failure) {
            ResultSet rows = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "next" -> !noRows;
                    case "getInt" -> depth;
                    default -> null;
                };
            });
            Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setQueryTimeout": assertEquals(5, args[0]); return null;
                    case "executeQuery":
                        assertEquals("SELECT @@session.autocommit", args[0]);
                        if (failure) throw new SQLException("fixture unavailable");
                        return rows;
                    case "close": closed.set(true); return null;
                    default: throw new AssertionError("Unexpected probe statement operation: " + method.getName());
                }
            });
            connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("createStatement".equals(method.getName())) return statement;
                throw new AssertionError("The probe must not change connection state: " + method.getName());
            });
        }
        public void onStatementCreated(Statement statement) { created.incrementAndGet(); }
        public void onStatementClosed(Statement statement) { released.incrementAndGet(); }
        void assertClosedAndRegistered() {
            assertTrue(closed.get());
            assertEquals(1, created.get());
            assertEquals(1, released.get());
        }
    }
}
