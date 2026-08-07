package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

final class TaskStatementTrackingConnection {

    private TaskStatementTrackingConnection() {
    }

    static Connection wrap(Connection connection, TaskExecutionContext context) {
        if (connection == null || context == null) {
            return connection;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (isObjectMethod(method)) {
                return invokeObjectMethod(proxy, method, args);
            }
            Object result = invoke(connection, method, args);
            return result instanceof Statement statement ? track(statement, context) : result;
        };
        return (Connection) Proxy.newProxyInstance(TaskStatementTrackingConnection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }

    private static Statement track(Statement statement, TaskExecutionContext context) {
        context.onStatementCreated(statement);
        AtomicBoolean closed = new AtomicBoolean();
        InvocationHandler handler = (proxy, method, args) -> {
            if (isObjectMethod(method)) {
                return invokeObjectMethod(proxy, method, args);
            }
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                try {
                    return invoke(statement, method, args);
                } finally {
                    if (closed.compareAndSet(false, true)) {
                        context.onStatementClosed(statement);
                    }
                }
            }
            return invoke(statement, method, args);
        };
        return (Statement) Proxy.newProxyInstance(TaskStatementTrackingConnection.class.getClassLoader(),
                new Class<?>[] {statementInterface(statement)}, handler);
    }

    private static Class<? extends Statement> statementInterface(Statement statement) {
        if (statement instanceof CallableStatement) {
            return CallableStatement.class;
        }
        if (statement instanceof PreparedStatement) {
            return PreparedStatement.class;
        }
        return Statement.class;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static boolean isObjectMethod(Method method) {
        return method.getDeclaringClass() == Object.class;
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> TaskStatementTrackingConnection.class.getSimpleName();
            default -> throw new IllegalStateException("Unsupported Object method: " + method.getName());
        };
    }
}
