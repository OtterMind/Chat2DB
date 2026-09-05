package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.CheckConstraintInfo;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlMetaDataTest {

    @AfterEach
    void removeContext() {
        Chat2DBContext.removeContext();
    }

    @Test
    void checkConstraintsUsesParameterizedInformationSchemaQuery() {
        withMysqlVersion("8.0.16");
        CheckConstraintRow named = new CheckConstraintRow("chk_age_positive", "`age` >= 0", "YES");
        CheckConstraintRow serverNamed = new CheckConstraintRow("obj008_check_test_chk_1",
                "(`email` is not null) or (`name` = _utf8mb4'anonymous')", "NO");
        JdbcFixture fixture = JdbcFixture.withRows(List.of(named, serverNamed));

        List<CheckConstraintInfo> constraints = new MysqlMetaData().checkConstraints(fixture.connection(),
                new TableMetadataRequest("obj_db", null, "obj008_check_test"));

        assertTrue(fixture.preparedSql.contains("tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ?"), fixture.preparedSql);
        assertEquals(List.of("obj_db", "obj008_check_test"), fixture.parameters);
        assertEquals(2, constraints.size());
        assertEquals("chk_age_positive", constraints.get(0).getName());
        assertEquals("`age` >= 0", constraints.get(0).getExpression());
        assertEquals(Boolean.TRUE, constraints.get(0).getEnforced());
        assertEquals("obj008_check_test_chk_1", constraints.get(1).getName());
        assertEquals(Boolean.FALSE, constraints.get(1).getEnforced());
        assertEquals("obj008_check_test", constraints.get(1).getTableName());
    }

    @Test
    void checkConstraintsSkipsMetadataOnUnsupportedVersionExplicitly() {
        withMysqlVersion("8.0.15");
        JdbcFixture fixture = JdbcFixture.withRows(List.of());

        List<CheckConstraintInfo> constraints = new MysqlMetaData().checkConstraints(fixture.connection(),
                new TableMetadataRequest("obj_db", null, "obj008_check_test"));

        assertEquals(List.of(), constraints);
        assertEquals(null, fixture.preparedSql);
        assertEquals(List.of(), fixture.parameters);
    }

    @Test
    void checkConstraintsDoesNotSwallowUnexpectedMetadataFailures() {
        withMysqlVersion("8.0.16");
        Connection connection = connectionThrowingOnPrepare(new SQLException("permission denied"));

        assertThrows(RuntimeException.class, () -> new MysqlMetaData().checkConstraints(connection,
                new TableMetadataRequest("obj_db", null, "obj008_check_test")));
    }

    private static void withMysqlVersion(String version) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MYSQL");
        connectInfo.setDbVersion(version);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("MYSQL");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    private static Connection connectionThrowingOnPrepare(SQLException exception) {
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                throw exception;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private record CheckConstraintRow(String name, String expression, String enforced) {
    }

    private static final class JdbcFixture {
        private final List<CheckConstraintRow> rows;
        private final List<String> parameters = new ArrayList<>();
        private String preparedSql;

        private JdbcFixture(List<CheckConstraintRow> rows) {
            this.rows = rows;
        }

        private static JdbcFixture withRows(List<CheckConstraintRow> rows) {
            return new JdbcFixture(rows);
        }

        private Connection connection() {
            ResultSet resultSet = resultSet(rows);
            PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setString" -> {
                    parameters.add((String) args[1]);
                    yield null;
                }
                case "execute" -> true;
                case "getResultSet" -> resultSet;
                default -> defaultValue(method.getReturnType());
            });
            return proxy(Connection.class, (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    preparedSql = (String) args[0];
                    return statement;
                }
                return defaultValue(method.getReturnType());
            });
        }

        private static ResultSet resultSet(List<CheckConstraintRow> rows) {
            Iterator<CheckConstraintRow> iterator = rows.iterator();
            CheckConstraintRow[] current = new CheckConstraintRow[1];
            return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    boolean hasNext = iterator.hasNext();
                    if (hasNext) {
                        current[0] = iterator.next();
                    }
                    yield hasNext;
                }
                case "getString" -> switch ((Integer) args[0]) {
                    case 1 -> current[0].name();
                    case 2 -> current[0].expression();
                    case 3 -> current[0].enforced();
                    default -> null;
                };
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
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
}
