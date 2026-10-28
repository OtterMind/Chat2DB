package ai.chat2db.plugin.clickhouse;

import ai.chat2db.community.domain.api.model.metadata.Function;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseFunctionMetadataTest {

    @Test
    void functionDetailFiltersByRequestedName() {
        List<String> sql = new ArrayList<>();
        List<String> parameters = new ArrayList<>();

        Function function = new ClickHouseMetaData().function(
            connection(sql, parameters), "db", "schema", "wanted");

        assertEquals("wanted ddl", function.getFunctionBody());
        assertEquals(List.of("wanted"), parameters);
        assertTrue(sql.get(0).endsWith(" AND name = ?"), sql.get(0));
    }

    private static Connection connection(List<String> sql, List<String> parameters) {
        return (Connection) Proxy.newProxyInstance(
            ClickHouseFunctionMetadataTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (!"prepareStatement".equals(method.getName())) {
                    throw new AssertionError("Unexpected Connection call: " + method.getName());
                }
                sql.add((String) args[0]);
                return statement(parameters);
            });
    }

    private static PreparedStatement statement(List<String> parameters) {
        return (PreparedStatement) Proxy.newProxyInstance(
            ClickHouseFunctionMetadataTest.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setString" -> {
                    assertEquals(1, args[0]);
                    parameters.add((String) args[1]);
                    yield null;
                }
                case "execute" -> true;
                case "getResultSet" -> functionResultSet(parameters.isEmpty() ? null : parameters.get(0));
                case "close" -> null;
                default -> throw new AssertionError("Unexpected PreparedStatement call: " + method.getName());
            });
    }

    private static ResultSet functionResultSet(String requestedName) {
        String[] definitions = "wanted".equals(requestedName)
            ? new String[]{"wanted ddl"}
            : new String[]{"other ddl", "wanted ddl"};
        int[] row = {-1};
        return (ResultSet) Proxy.newProxyInstance(
            ClickHouseFunctionMetadataTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++row[0] < definitions.length;
                case "getString" -> {
                    if (!"ddl".equals(args[0])) {
                        throw new AssertionError("Unexpected ResultSet label: " + args[0]);
                    }
                    yield definitions[row[0]];
                }
                case "close" -> null;
                default -> throw new AssertionError("Unexpected ResultSet call: " + method.getName());
            });
    }
}
