package ai.chat2db.plugin.xugudb;

import ai.chat2db.community.domain.api.model.metadata.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XUGUDBViewMetadataTest {

    @Test
    void viewsMapsEachRowToItsOwnTableInstance() {
        List<Table> views = new XUGUDBMetaData().views(
                connection(viewResultSet(new String[][]{{"V1", "SELECT 1"}, {"V2", "SELECT 2"}})),
                "DB", "SCH");

        assertEquals(2, views.size());
        assertNotSame(views.get(0), views.get(1));
        assertEquals("V1", views.get(0).getName());
        assertEquals("SELECT 1", views.get(0).getDdl());
        assertEquals("V2", views.get(1).getName());
        assertEquals("SELECT 2", views.get(1).getDdl());
    }

    @Test
    void viewsReturnsEmptyListWhenTheResultSetIsEmpty() {
        List<Table> views = new XUGUDBMetaData().views(
                connection(viewResultSet(new String[0][0])), "DB", "SCH");

        assertTrue(views.isEmpty());
    }

    private static Connection connection(ResultSet resultSet) {
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                XUGUDBViewMetadataTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                XUGUDBViewMetadataTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName())
                        ? statement : defaultValue(method.getReturnType()));
    }

    private static ResultSet viewResultSet(String[][] rows) {
        int[] row = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                XUGUDBViewMetadataTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++row[0] < rows.length;
                    case "getString" -> "VIEW_NAME".equals(args[0]) ? rows[row[0]][0] : rows[row[0]][1];
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
