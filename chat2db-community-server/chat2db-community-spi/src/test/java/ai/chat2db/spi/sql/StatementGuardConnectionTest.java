package ai.chat2db.spi.sql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatementGuardConnectionTest {

    @Test
    void guardsPreparedAndDirectStatementsBeforeDriverInvocation() throws Exception {
        List<String> guarded = new ArrayList<>();
        AtomicInteger preparedByDriver = new AtomicInteger();
        Connection connection = connection(preparedByDriver);
        Connection guardedConnection = StatementGuardConnection.wrap(connection, guarded::add);

        guardedConnection.prepareStatement("select * from orders");
        guardedConnection.createStatement().execute("delete from orders");

        assertEquals(List.of("select * from orders", "delete from orders"), guarded);
        assertEquals(1, preparedByDriver.get());
    }

    @Test
    void rejectionHappensBeforePreparedStatementCreation() {
        AtomicInteger preparedByDriver = new AtomicInteger();
        Connection connection = connection(preparedByDriver);
        Connection guardedConnection = StatementGuardConnection.wrap(connection, sql -> {
            throw new Denied();
        });

        assertThrows(Denied.class, () -> guardedConnection.prepareStatement("drop table orders"));

        assertEquals(0, preparedByDriver.get());
    }

    private Connection connection(AtomicInteger preparedByDriver) {
        PreparedStatement preparedStatement = proxy(PreparedStatement.class);
        Statement statement = proxy(Statement.class);
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        preparedByDriver.incrementAndGet();
                        yield preparedStatement;
                    }
                    case "createStatement" -> statement;
                    default -> defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private Object defaultValue(Class<?> type) {
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

    private static final class Denied extends RuntimeException {
    }
}
