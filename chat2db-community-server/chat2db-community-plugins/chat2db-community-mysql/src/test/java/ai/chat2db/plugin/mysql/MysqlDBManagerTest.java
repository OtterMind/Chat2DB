package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static TaskExecutionContext context() {
        return (TaskExecutionContext) Proxy.newProxyInstance(MysqlDBManagerTest.class.getClassLoader(),
                new Class<?>[] {TaskExecutionContext.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
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
