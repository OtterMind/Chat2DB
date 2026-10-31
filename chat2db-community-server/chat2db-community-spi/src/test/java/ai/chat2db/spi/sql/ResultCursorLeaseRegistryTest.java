package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.model.result.snapshot.ResultCursorHold;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cursor a leased large value reads from: it stays open while a snapshot holds it, and it is closed exactly
 * once when the last hold is gone.
 */
class ResultCursorLeaseRegistryTest {

    @AfterEach
    void tearDown() {
        ResultCursorLeaseRegistry.releaseAll();
        ResultCursorLeaseRegistry.end();
    }

    @Test
    void aLeaseKeepsTheStatementOpenUntilItsLastHoldIsGone() {
        AtomicBoolean statementClosed = new AtomicBoolean();
        AtomicBoolean connectionClosed = new AtomicBoolean();
        ConnectInfo connectInfo = connectInfo(connection(connectionClosed));

        ResultCursorLeaseRegistry.begin(connectInfo, statement(statementClosed));
        ResultCursorHold first = ResultCursorLeaseRegistry.holdForCurrentExecution();
        ResultCursorHold second = ResultCursorLeaseRegistry.holdForCurrentExecution();

        assertNotNull(first, "a streaming execution can lease its cursor");
        assertTrue(ResultCursorLeaseRegistry.isPinned(), "the execution must keep the cursor it leased");
        assertTrue(ResultCursorLeaseRegistry.isHeld(connectInfo), "the connection belongs to the lease");

        first.close();
        assertFalse(statementClosed.get(), "the second hold still reads from this cursor");
        second.close();

        assertTrue(statementClosed.get(), "the cursor is closed once nothing holds it");
        assertTrue(connectionClosed.get(), "and the connection is given back");
        assertEquals(0, ResultCursorLeaseRegistry.openLeases());
        assertFalse(ResultCursorLeaseRegistry.isHeld(connectInfo));
    }

    @Test
    void anExecutionThatLeasesNothingKeepsItsOwnStatement() {
        ResultCursorLeaseRegistry.begin(connectInfo(connection(new AtomicBoolean())), statement(new AtomicBoolean()));

        assertFalse(ResultCursorLeaseRegistry.isPinned(), "nothing was leased, so the executor closes the statement");
        assertEquals(0, ResultCursorLeaseRegistry.openLeases());
    }

    @Test
    void theLeaseLimitStopsHandingOutHolds() {
        for (int index = 0; index < ResultCursorLeaseRegistry.MAX_LEASES; index++) {
            ResultCursorLeaseRegistry.begin(connectInfo(connection(new AtomicBoolean())), statement(new AtomicBoolean()));
            assertNotNull(ResultCursorLeaseRegistry.holdForCurrentExecution(), "lease " + index);
        }

        ResultCursorLeaseRegistry.begin(connectInfo(connection(new AtomicBoolean())), statement(new AtomicBoolean()));

        assertNull(ResultCursorLeaseRegistry.holdForCurrentExecution(),
                "beyond the limit the value is captured the usual way instead");
        assertEquals(ResultCursorLeaseRegistry.MAX_LEASES, ResultCursorLeaseRegistry.openLeases());
    }

    @Test
    void everyStatementOfOneRequestKeepsItsCursorUntilTheLeaseIsGone() {
        AtomicBoolean firstClosed = new AtomicBoolean();
        AtomicBoolean secondClosed = new AtomicBoolean();
        ConnectInfo connectInfo = connectInfo(connection(new AtomicBoolean()));
        Statement first = statement(firstClosed);
        Statement second = statement(secondClosed);

        ResultCursorLeaseRegistry.begin(connectInfo, first);
        ResultCursorLeaseRegistry.begin(connectInfo, second);

        assertTrue(ResultCursorLeaseRegistry.isCursorAnnounced(first),
                "the response of the first statement is not read yet, so its cursor stays open");
        ResultCursorHold hold = ResultCursorLeaseRegistry.holdForCurrentExecution();
        assertNotNull(hold, "the cursors of the request are leased from");

        Chat2DBContext.removeContext();

        assertFalse(firstClosed.get(), "the lease reads from both cursors");
        assertFalse(secondClosed.get());

        hold.close();

        assertTrue(firstClosed.get(), "releasing the reader closes every cursor of the request");
        assertTrue(secondClosed.get());
    }

    @Test
    void aCursorAnnouncedAfterItsLeaseWasGivenUpIsNotLeftOpen() {
        AtomicBoolean firstClosed = new AtomicBoolean();
        AtomicBoolean secondClosed = new AtomicBoolean();
        ConnectInfo connectInfo = connectInfo(connection(new AtomicBoolean()));
        ResultCursorLeaseRegistry.begin(connectInfo, statement(firstClosed));
        assertNotNull(ResultCursorLeaseRegistry.holdForCurrentExecution());

        // The idle sweep can give a lease up while the request that announced it still runs
        ResultCursorLeaseRegistry.evictIdle(
                System.currentTimeMillis() + ResultCursorLeaseRegistry.IDLE_TIMEOUT_MS + 1000L);
        assertTrue(firstClosed.get(), "the idle lease closes its own cursor");

        ResultCursorLeaseRegistry.begin(connectInfo, statement(secondClosed));
        Chat2DBContext.removeContext();

        assertTrue(secondClosed.get(),
                "a cursor announced after the lease was given up is closed with its connection context");
    }

    @Test
    void aRequestThatLeasesNothingClosesEveryCursorItAnnounced() {
        AtomicBoolean firstClosed = new AtomicBoolean();
        AtomicBoolean secondClosed = new AtomicBoolean();
        ConnectInfo connectInfo = connectInfo(connection(new AtomicBoolean()));

        ResultCursorLeaseRegistry.begin(connectInfo, statement(firstClosed));
        ResultCursorLeaseRegistry.begin(connectInfo, statement(secondClosed));
        Chat2DBContext.removeContext();

        assertTrue(firstClosed.get(), "a cursor nobody leased is given up with its connection context");
        assertTrue(secondClosed.get());
    }

    @Test
    void anIdleLeaseIsClosedByTheSweep() {
        AtomicBoolean statementClosed = new AtomicBoolean();
        ConnectInfo connectInfo = connectInfo(connection(new AtomicBoolean()));
        ResultCursorLeaseRegistry.begin(connectInfo, statement(statementClosed));
        assertNotNull(ResultCursorLeaseRegistry.holdForCurrentExecution());

        int closed = ResultCursorLeaseRegistry.evictIdle(
                System.currentTimeMillis() + ResultCursorLeaseRegistry.IDLE_TIMEOUT_MS + 1000L);

        assertEquals(1, closed, "a lease nobody reads from is closed");
        assertTrue(statementClosed.get(), "the cursor of an idle lease is closed");
        assertEquals(0, ResultCursorLeaseRegistry.openLeases());
    }

    private static ConnectInfo connectInfo(Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConnection(connection);
        connectInfo.setLastAccessTime(new Date());
        return connectInfo;
    }

    private static Connection connection(AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(
                ResultCursorLeaseRegistryTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return closed.get();
                        case "close":
                            closed.set(true);
                            return null;
                        case "toString":
                            return "TestConnection";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Statement statement(AtomicBoolean closed) {
        return (Statement) Proxy.newProxyInstance(
                ResultCursorLeaseRegistryTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "close":
                            closed.set(true);
                            return null;
                        case "isClosed":
                            return closed.get();
                        case "toString":
                            return "TestStatement";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
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
