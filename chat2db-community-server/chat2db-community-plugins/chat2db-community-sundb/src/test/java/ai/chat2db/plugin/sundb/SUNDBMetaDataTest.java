package ai.chat2db.plugin.sundb;

import ai.chat2db.community.domain.api.model.metadata.Procedure;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SUNDBMetaDataTest {

    /**
     * Every result row must be mapped to its own {@link Procedure} instance;
     * a single shared instance would make all list entries carry the last
     * procedure name.
     */
    @Test
    void proceduresMapsEachRowToItsOwnInstance() {
        List<Procedure> procedures = new SUNDBMetaData()
                .procedures(proceduresConnection("P1", "P2"), "db", "SCH");

        assertEquals(2, procedures.size());
        assertEquals("P1", procedures.get(0).getProcedureName());
        assertEquals("P2", procedures.get(1).getProcedureName());
    }

    private static Connection proceduresConnection(String... objectNames) {
        ResultSet resultSet = objectNameResultSet(objectNames);
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                SUNDBMetaDataTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    default -> defaultValue(method.getReturnType());
                });
        DatabaseMetaData databaseMetaData = (DatabaseMetaData) Proxy.newProxyInstance(
                SUNDBMetaDataTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> "getUserName".equals(method.getName())
                        ? "TESTER" : defaultValue(method.getReturnType()));
        return (Connection) Proxy.newProxyInstance(
                SUNDBMetaDataTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> statement;
                    case "getMetaData" -> databaseMetaData;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ResultSet objectNameResultSet(String... objectNames) {
        int[] row = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                SUNDBMetaDataTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++row[0] < objectNames.length;
                    case "getString" -> "OBJECT_NAME".equals(args[0]) ? objectNames[row[0]] : null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
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
}
