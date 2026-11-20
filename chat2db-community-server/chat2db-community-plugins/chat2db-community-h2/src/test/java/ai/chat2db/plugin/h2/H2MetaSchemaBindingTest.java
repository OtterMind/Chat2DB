package ai.chat2db.plugin.h2;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class H2MetaSchemaBindingTest {

    @Test
    void routineAndTriggerDetailsFilterBySchemaInsteadOfCatalog() {
        List<String> sql = new ArrayList<>();
        Connection connection = connection(sql);
        H2Meta metaData = new H2Meta();

        metaData.function(connection, "CATALOG", "APP", "F1");
        metaData.procedure(connection, "CATALOG", "APP", "P1");
        metaData.trigger(connection, "CATALOG", "APP", "T1");

        assertTrue(sql.get(0).contains("ROUTINE_SCHEMA ='APP'"), sql.get(0));
        assertTrue(sql.get(1).contains("ROUTINE_SCHEMA ='APP'"), sql.get(1));
        assertTrue(sql.get(2).contains("TRIGGER_SCHEMA = 'APP'"), sql.get(2));
    }

    private static Connection connection(List<String> sql) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
            H2MetaSchemaBindingTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> false;
                case "close" -> null;
                default -> throw new AssertionError("Unexpected ResultSet call: " + method.getName());
            });
        return (Connection) Proxy.newProxyInstance(
            H2MetaSchemaBindingTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (!"prepareStatement".equals(method.getName())) {
                    throw new AssertionError("Unexpected Connection call: " + method.getName());
                }
                sql.add((String) args[0]);
                return Proxy.newProxyInstance(
                    H2MetaSchemaBindingTest.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (statement, statementMethod, statementArgs) -> switch (statementMethod.getName()) {
                        case "execute" -> true;
                        case "getResultSet" -> resultSet;
                        case "close" -> null;
                        default -> throw new AssertionError(
                            "Unexpected PreparedStatement call: " + statementMethod.getName());
                    });
            });
    }
}
