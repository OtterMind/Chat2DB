package ai.chat2db.plugin.dm;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

final class DMExplainClient {

    String getExplainInfo(Connection connection, String sql) throws SQLException {
        try {
            Object dmConnection = resolveDmConnection(connection);
            ClassLoader driverClassLoader = classLoaderOf(dmConnection);
            Class<?> dmConnectionClass = Class.forName("dm.jdbc.driver.DmdbConnection", true, driverClassLoader);
            Method method = dmConnectionClass.getMethod("getExplainInfo", String.class);
            return (String) method.invoke(dmConnection, sql);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("DM getExplainInfo failed", cause);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("DM JDBC driver does not support getExplainInfo", e);
        }
    }

    private Object resolveDmConnection(Connection connection) throws SQLException, ClassNotFoundException {
        Connection unwrapped = safeUnwrapConnection(connection);
        ClassLoader driverClassLoader = classLoaderOf(unwrapped);
        Class<?> dmConnectionClass = Class.forName("dm.jdbc.driver.DmdbConnection", true, driverClassLoader);

        if (dmConnectionClass.isInstance(connection)) {
            return connection;
        }
        if (dmConnectionClass.isInstance(unwrapped)) {
            return unwrapped;
        }

        Object fromOuter = safeUnwrapAs(connection, dmConnectionClass);
        if (fromOuter != null) {
            return fromOuter;
        }
        Object fromInner = safeUnwrapAs(unwrapped, dmConnectionClass);
        if (fromInner != null) {
            return fromInner;
        }
        throw new SQLException("DM JDBC driver does not support getExplainInfo");
    }

    private Connection safeUnwrapConnection(Connection connection) {
        try {
            Connection unwrapped = connection.unwrap(Connection.class);
            return unwrapped == null ? connection : unwrapped;
        } catch (SQLException e) {
            return connection;
        }
    }

    private Object safeUnwrapAs(Connection connection, Class<?> dmConnectionClass) {
        try {
            return connection.unwrap(dmConnectionClass.asSubclass(Connection.class));
        } catch (SQLException e) {
            return null;
        }
    }

    private ClassLoader classLoaderOf(Object target) {
        ClassLoader loader = target.getClass().getClassLoader();
        if (loader != null) {
            return loader;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader == null ? DMExplainClient.class.getClassLoader() : contextLoader;
    }
}
