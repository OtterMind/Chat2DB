package ai.chat2db.plugin.h2;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H2DBManagerSecurityTest {

    @Test
    void exportSchemaEscapesSchemaName() throws Exception {
        List<String> captured = new ArrayList<>();
        Connection connection = captureConnection(captured);
        AsyncContext asyncContext = new AsyncContext(null, null, tempFile(), false);

        new H2DBManager().exportDatabase(connection, "TEST", "EVIL\" SCHEMA", asyncContext);

        assertEquals(List.of("SCRIPT NODATA NOPASSWORDS NOSETTINGS DROP SCHEMA \"EVIL\"\" SCHEMA\";"),
            captured);
    }

    @Test
    void exportSchemaAppliesNodataSentinelBeforeSubstitutingSchemaName() throws Exception {
        List<String> captured = new ArrayList<>();
        Connection connection = captureConnection(captured);
        AsyncContext asyncContext = new AsyncContext(null, null, tempFile(), true);

        new H2DBManager().exportDatabase(connection, "TEST", "ANODATA", asyncContext);

        assertEquals(List.of("SCRIPT  NOPASSWORDS NOSETTINGS DROP SCHEMA \"ANODATA\";"), captured);
    }

    @Test
    void connectDatabaseEscapesSetSchemaName() throws Exception {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setSchemaName("EVIL\" SCHEMA");
        Chat2DBContext.putContext(connectInfo);
        try {
            List<String> captured = new ArrayList<>();
            Connection connection = proxy(Connection.class, (p, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    captured.add((String) args[0]);
                    return proxy(PreparedStatement.class, (p2, method2, args2) -> {
                        throw new SQLException("stop after capture");
                    });
                }
                return defaultValue(method.getReturnType());
            });

            new H2DBManager().connectDatabase(connection, "TEST");

            assertEquals(List.of("SET SCHEMA \"EVIL\"\" SCHEMA\""), captured);
        } finally {
            Chat2DBContext.removeContext();
        }
    }

    private static File tempFile() throws Exception {
        File file = File.createTempFile("h2-export", ".sql");
        file.deleteOnExit();
        return file;
    }

    private static Connection captureConnection(List<String> captured) {
        return proxy(Connection.class, (p, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                captured.add((String) args[0]);
                return proxy(PreparedStatement.class, (p2, method2, args2) -> {
                    if ("executeQuery".equals(method2.getName())) {
                        return proxy(ResultSet.class, (p3, method3, args3) -> {
                            if ("next".equals(method3.getName())) {
                                return false;
                            }
                            return defaultValue(method3.getReturnType());
                        });
                    }
                    return defaultValue(method2.getReturnType());
                });
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
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
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
