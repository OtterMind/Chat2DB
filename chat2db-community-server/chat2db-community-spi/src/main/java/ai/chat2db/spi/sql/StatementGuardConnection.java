package ai.chat2db.spi.sql;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.function.Consumer;

final class StatementGuardConnection {

    private StatementGuardConnection() {
    }

    static Connection wrap(Connection connection, Consumer<String> statementGuard) {
        if (connection == null || statementGuard == null) {
            return connection;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (isObjectMethod(method)) {
                return invokeObjectMethod(proxy, method, args, "TaskGuardedConnection");
            }
            if (preparesSql(method, args)) {
                statementGuard.accept((String) args[0]);
            }
            Object result = invoke(connection, method, args);
            if ("createStatement".equals(method.getName()) && result instanceof Statement statement) {
                return wrapStatement(statement, statementGuard);
            }
            return result;
        };
        return (Connection) Proxy.newProxyInstance(StatementGuardConnection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }

    private static Statement wrapStatement(Statement statement, Consumer<String> statementGuard) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (isObjectMethod(method)) {
                return invokeObjectMethod(proxy, method, args, "TaskGuardedStatement");
            }
            if (executesSql(method, args)) {
                statementGuard.accept((String) args[0]);
            }
            return invoke(statement, method, args);
        };
        return (Statement) Proxy.newProxyInstance(StatementGuardConnection.class.getClassLoader(),
                new Class<?>[] {Statement.class}, handler);
    }

    private static boolean preparesSql(Method method, Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof String
                && ("prepareStatement".equals(method.getName()) || "prepareCall".equals(method.getName()));
    }

    private static boolean executesSql(Method method, Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return false;
        }
        return switch (method.getName()) {
            case "addBatch", "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> true;
            default -> false;
        };
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

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args, String description) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> description;
            default -> throw new IllegalStateException("Unsupported Object method: " + method.getName());
        };
    }
}
