package ai.chat2db.plugin.sqlite;

import ai.chat2db.community.domain.api.model.metadata.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteViewMetadataTest {

    @Test
    void viewPreservesRequestedIdentityAlongsideDefinition() {
        Table view = new SqliteMetaData().view(
            connection("CREATE VIEW v_items AS SELECT * FROM items"), "main", "APP", "v_items");

        assertEquals("main", view.getDatabaseName());
        assertEquals("APP", view.getSchemaName());
        assertEquals("v_items", view.getName());
        assertEquals("CREATE VIEW v_items AS SELECT * FROM items", view.getDdl());
    }

    private static Connection connection(String definition) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
            SqliteViewMetadataTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            new java.lang.reflect.InvocationHandler() {
                private boolean beforeFirst = true;

                @Override
                public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> {
                            boolean hasRow = beforeFirst;
                            beforeFirst = false;
                            yield hasRow;
                        }
                        case "getString" -> {
                            if (!"sql".equals(args[0])) {
                                throw new AssertionError("Unexpected ResultSet label: " + args[0]);
                            }
                            yield definition;
                        }
                        case "close" -> null;
                        default -> throw new AssertionError("Unexpected ResultSet call: " + method.getName());
                    };
                }
            });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
            SqliteViewMetadataTest.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> true;
                case "getResultSet" -> resultSet;
                case "close" -> null;
                default -> throw new AssertionError("Unexpected PreparedStatement call: " + method.getName());
            });
        return (Connection) Proxy.newProxyInstance(
            SqliteViewMetadataTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    return statement;
                }
                throw new AssertionError("Unexpected Connection call: " + method.getName());
            });
    }
}
