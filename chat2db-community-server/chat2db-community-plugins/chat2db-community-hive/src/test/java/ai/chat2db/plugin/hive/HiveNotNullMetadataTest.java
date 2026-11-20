package ai.chat2db.plugin.hive;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiveNotNullMetadataTest {

    private static final String[] COLUMN_LABELS = {"col_name", "data_type", "comment"};

    @Test
    void notNullConstraintMapsToJdbcNoNullsValue() {
        String[][] rows = {
            {"# col_name", "", ""},
            {"id", "int", "identifier"},
            {"# Not Null Constraints", "", ""},
            {"Constraint:", "NN_ID", ""},
            {"Column:", "id", ""}
        };

        List<TableColumn> columns = new HiveMetaData().columns(connection(rows), "db", "APP", "items");

        assertEquals(1, columns.size());
        assertEquals("id", columns.get(0).getName());
        assertEquals(0, columns.get(0).getNullable());
    }

    private static Connection connection(String[][] rows) {
        ResultSet resultSet = resultSet(rows);
        return (Connection) Proxy.newProxyInstance(
            HiveNotNullMetadataTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (!"prepareStatement".equals(method.getName())) {
                    throw new AssertionError("Unexpected Connection call: " + method.getName());
                }
                return Proxy.newProxyInstance(
                    HiveNotNullMetadataTest.class.getClassLoader(),
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

    private static ResultSet resultSet(String[][] rows) {
        ResultSetMetaData metaData = (ResultSetMetaData) Proxy.newProxyInstance(
            HiveNotNullMetadataTest.class.getClassLoader(),
            new Class<?>[]{ResultSetMetaData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getColumnCount" -> COLUMN_LABELS.length;
                case "getColumnName" -> COLUMN_LABELS[(Integer) args[0] - 1];
                default -> throw new AssertionError("Unexpected ResultSetMetaData call: " + method.getName());
            });
        int[] row = {-1};
        return (ResultSet) Proxy.newProxyInstance(
            HiveNotNullMetadataTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++row[0] < rows.length;
                case "getMetaData" -> metaData;
                case "getString" -> rows[row[0]][columnIndex(args[0])];
                case "close" -> null;
                default -> throw new AssertionError("Unexpected ResultSet call: " + method.getName());
            });
    }

    private static int columnIndex(Object selector) {
        if (selector instanceof Integer index) {
            return index - 1;
        }
        for (int i = 0; i < COLUMN_LABELS.length; i++) {
            if (COLUMN_LABELS[i].equals(selector)) {
                return i;
            }
        }
        throw new AssertionError("Unexpected ResultSet label: " + selector);
    }
}
