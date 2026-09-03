package ai.chat2db.plugin.mysql.account;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlAccountManagerTest {

    @Test
    void showGrantsReadsBackDirectAndInheritedColumnGrants() {
        AtomicReference<String> preparedSql = new AtomicReference<>();
        Connection connection = proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                preparedSql.set((String) args[0]);
                yield preparedStatement(List.of(
                        "GRANT SELECT (`id`, `email`) ON `app`.`orders` TO 'reader'@'%'",
                        "GRANT UPDATE (`status`) ON `app`.`orders` TO 'reader'@'%' WITH GRANT OPTION",
                        "GRANT SELECT ON `app`.* TO 'reader'@'%'"
                ));
            }
            default -> defaultValue(method.getReturnType());
        });

        List<String> grants = new MysqlAccountManager().showGrants(connection, "reader", "%");

        assertEquals("SHOW GRANTS FOR 'reader'@'%'", preparedSql.get());
        assertEquals(List.of(
                "GRANT SELECT (`id`, `email`) ON `app`.`orders` TO 'reader'@'%'",
                "GRANT UPDATE (`status`) ON `app`.`orders` TO 'reader'@'%' WITH GRANT OPTION",
                "GRANT SELECT ON `app`.* TO 'reader'@'%'"
        ), grants);
    }

    private static PreparedStatement preparedStatement(List<String> rows) {
        ResultSet resultSet = resultSet(rows);
        return proxy(PreparedStatement.class, (target, method, args) -> switch (method.getName()) {
            case "executeQuery" -> resultSet;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(List<String> rows) {
        AtomicReference<Integer> index = new AtomicReference<>(-1);
        return proxy(ResultSet.class, (target, method, args) -> switch (method.getName()) {
            case "next" -> {
                index.set(index.get() + 1);
                yield index.get() < rows.size();
            }
            case "getString" -> rows.get(index.get());
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == args[0];
                    default -> null;
                };
            }
            return handler.invoke(target, method, args);
        });
    }

    private static Object defaultValue(Class<?> returnType) {
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
