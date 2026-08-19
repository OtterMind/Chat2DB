package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class TaskStatementTrackingConnectionTest {

    @Test
    void preparedStatementReportsLifecycleAndDelegatesCancellation() throws Exception {
        AtomicInteger cancelCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        PreparedStatement delegate = proxy(PreparedStatement.class, (proxy, method, args) -> {
            if ("cancel".equals(method.getName())) {
                cancelCalls.incrementAndGet();
                return null;
            }
            if ("close".equals(method.getName())) {
                closeCalls.incrementAndGet();
                return null;
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return delegate;
            }
            return defaultValue(method.getReturnType());
        });
        AtomicReference<Statement> created = new AtomicReference<>();
        AtomicReference<Statement> closed = new AtomicReference<>();
        AtomicInteger closeEvents = new AtomicInteger();
        TaskExecutionContext context = proxy(TaskExecutionContext.class, (proxy, method, args) -> {
            if ("onStatementCreated".equals(method.getName())) {
                created.set((Statement) args[0]);
            } else if ("onStatementClosed".equals(method.getName())) {
                closed.set((Statement) args[0]);
                closeEvents.incrementAndGet();
            }
            return defaultValue(method.getReturnType());
        });

        Connection trackedConnection = TaskStatementTrackingConnection.wrap(connection, context);
        PreparedStatement trackedStatement = trackedConnection.prepareStatement("select 1");

        assertNotSame(delegate, trackedStatement);
        assertSame(delegate, created.get());
        trackedStatement.cancel();
        trackedStatement.close();

        assertEquals(1, cancelCalls.get());
        assertEquals(1, closeCalls.get());
        assertEquals(1, closeEvents.get());
        assertSame(delegate, closed.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(TaskStatementTrackingConnectionTest.class.getClassLoader(),
                new Class<?>[] {type}, handler);
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
