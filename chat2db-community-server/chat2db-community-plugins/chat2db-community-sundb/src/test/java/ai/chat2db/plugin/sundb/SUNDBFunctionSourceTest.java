package ai.chat2db.plugin.sundb;

import ai.chat2db.community.domain.api.model.metadata.Function;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SUNDBFunctionSourceTest {

    @Test
    void functionReadsEverySourceLineInLineOrder() {
        List<String> sql = new ArrayList<>();

        Function function = new SUNDBMetaData().function(
            connection(sql, "line 1", "line 2"), "db", "APP", "F1");

        assertEquals("line 1\nline 2\n", function.getFunctionBody());
        assertTrue(sql.get(0).toLowerCase(Locale.ROOT).endsWith(" order by line"), sql.get(0));
    }

    private static Connection connection(List<String> sql, String... sourceLines) {
        DatabaseMetaData databaseMetaData = (DatabaseMetaData) Proxy.newProxyInstance(
            SUNDBFunctionSourceTest.class.getClassLoader(),
            new Class<?>[]{DatabaseMetaData.class},
            (proxy, method, args) -> {
                if ("getUserName".equals(method.getName())) {
                    return "TESTER";
                }
                throw new AssertionError("Unexpected DatabaseMetaData call: " + method.getName());
            });
        ResultSet resultSet = sourceResultSet(sourceLines);
        return (Connection) Proxy.newProxyInstance(
            SUNDBFunctionSourceTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMetaData" -> databaseMetaData;
                case "prepareStatement" -> {
                    sql.add((String) args[0]);
                    yield statement(resultSet);
                }
                default -> throw new AssertionError("Unexpected Connection call: " + method.getName());
            });
    }

    private static PreparedStatement statement(ResultSet resultSet) {
        return (PreparedStatement) Proxy.newProxyInstance(
            SUNDBFunctionSourceTest.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> true;
                case "getResultSet" -> resultSet;
                case "close" -> null;
                default -> throw new AssertionError("Unexpected PreparedStatement call: " + method.getName());
            });
    }

    private static ResultSet sourceResultSet(String... sourceLines) {
        int[] row = {-1};
        return (ResultSet) Proxy.newProxyInstance(
            SUNDBFunctionSourceTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++row[0] < sourceLines.length;
                case "getString" -> {
                    if (!(args[0] instanceof String label) || !"TEXT".equalsIgnoreCase(label)) {
                        throw new AssertionError("Unexpected ResultSet label: " + args[0]);
                    }
                    yield sourceLines[row[0]];
                }
                case "close" -> null;
                default -> throw new AssertionError("Unexpected ResultSet call: " + method.getName());
            });
    }
}
