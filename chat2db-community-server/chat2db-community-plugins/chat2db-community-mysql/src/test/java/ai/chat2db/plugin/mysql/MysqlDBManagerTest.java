package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlDBManagerTest {

    @Test
    void buildsDropDatabaseSqlWithStrictIdentifierEscaping() {
        TestMysqlDBManager manage = new TestMysqlDBManager();

        manage.dropDatabase(null, "a`; DROP DATABASE b; --");

        assertEquals("DROP DATABASE `a``; DROP DATABASE b; --`", manage.sql);
    }

    @Test
    void rejectsSchemaDropInsteadOfMappingItToDatabaseDrop() {
        MysqlDBManager manage = new MysqlDBManager();

        assertThrows(BusinessException.class, () -> manage.dropSchema(null, "app_db", "app_schema"));
    }

    @Test
    void singleTableStatementFailurePropagatesToTaskCaller() {
        MysqlDBManager manager = new MysqlDBManager();
        SQLException failure = new SQLException("Could not read table DDL");
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        throw failure;
                    }
                    return defaultValue(method.getReturnType());
                });

        SQLException thrown = assertThrows(SQLException.class,
                () -> manager.exportTable(connection, "app", "public", "orders", false, context()));

        assertSame(failure, thrown);
    }

    @Test
    void eventExportWritesExecutableRoundTripDdlWithQuotedIdentifiers() throws SQLException {
        List<String> preparedSql = new ArrayList<>();
        List<String> output = new ArrayList<>();
        ResultSet resultSet = proxy(ResultSet.class, new java.lang.reflect.InvocationHandler() {
            private boolean read;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> !read && (read = true);
                    case "getString" -> "CREATE DEFINER=`root`@`%` EVENT `analytics`.`daily``rollup` "
                            + "ON SCHEDULE EVERY 1 DAY DO SELECT 1";
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            }
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> resultSet;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                preparedSql.add((String) args[0]);
                yield statement;
            }
            default -> defaultValue(method.getReturnType());
        });
        TaskExecutionContext context = proxy(TaskExecutionContext.class, (proxy, method, args) -> {
            if ("write".equals(method.getName())) {
                output.add((String) args[0]);
            }
            return defaultValue(method.getReturnType());
        });

        new MysqlDBManager().exportEvent(connection, "analytics", "daily`rollup", context);

        assertEquals(1, preparedSql.size());
        assertTrue(preparedSql.get(0).contains("`analytics`.`daily``rollup`"));
        assertFalse(preparedSql.get(0).contains("DROP EVENT"));
        String export = String.join("", output);
        assertTrue(export.contains("DROP EVENT IF EXISTS `analytics`.`daily``rollup`;"));
        assertTrue(export.contains("CREATE DEFINER=`root`@`%` EVENT `analytics`.`daily``rollup`"));
        assertTrue(export.contains("delimiter ;;"));
        assertTrue(export.contains("delimiter ;"));
    }

    private static TaskExecutionContext context() {
        return (TaskExecutionContext) Proxy.newProxyInstance(MysqlDBManagerTest.class.getClassLoader(),
                new Class<?>[] {TaskExecutionContext.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
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

    private static class TestMysqlDBManager extends MysqlDBManager {
        private String sql;

        @Override
        void executeDropSql(Connection connection, String sql) {
            this.sql = sql;
        }
    }
}
