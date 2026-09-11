package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSQLExecutorExportTest {

    private static final String TEST_DB_TYPE = "SQL_EXECUTOR_EXPORT_TEST";

    private IPlugin previousPlugin;

    @BeforeEach
    void setUpContext() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin());
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDownContext() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void exportSkipsLeadingUpdateCountsAndWritesFirstResultSet() {
        List<List<String>> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        Connection connection = connection(statement(
                JdbcResult.update(0),
                JdbcResult.update(1),
                JdbcResult.resultSet(
                        columns("id", "name"),
                        row("1", "alpha"),
                        row("2", "beta")
                )
        ));

        new DefaultSQLExecutor().execute(connection, "batch sql", headerList -> headers.add(headerNames(headerList)),
                row -> rows.add(new ArrayList<>(row)), JDBCDataValue::getString, false);

        assertEquals(List.of(List.of("id", "name")), headers);
        assertEquals(List.of(List.of("1", "alpha"), List.of("2", "beta")), rows);
    }

    @Test
    void exportStillWritesPlainSelectResultSet() {
        List<List<String>> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        Connection connection = connection(statement(
                JdbcResult.resultSet(
                        columns("name"),
                        row("plain-select")
                )
        ));

        new DefaultSQLExecutor().execute(connection, "select name from test", headerList -> headers.add(headerNames(headerList)),
                row -> rows.add(new ArrayList<>(row)), JDBCDataValue::getString, false);

        assertEquals(List.of(List.of("name")), headers);
        assertEquals(List.of(List.of("plain-select")), rows);
    }

    @Test
    void exportAppliesJdbcMaxRowsAndStopsReadingAtTheSameLimit() {
        List<List<String>> rows = new ArrayList<>();
        StatementHandler statementHandler = new StatementHandler(List.of(JdbcResult.resultSet(
                columns("name"), row("alpha"), row("beta"), row("gamma"))));
        Connection connection = connection(proxy(PreparedStatement.class, statementHandler));

        new DefaultSQLExecutor().execute(connection, "select name from test", ignored -> {
        }, row -> rows.add(new ArrayList<>(row)), JDBCDataValue::getString, false, null, 2);

        assertEquals(2, statementHandler.maxRows);
        assertEquals(List.of(List.of("alpha"), List.of("beta")), rows);
    }

    @Test
    void exportWritesSelectedResultSet() {
        List<List<String>> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        Connection connection = connection(statement(
                JdbcResult.resultSet(
                        columns("first_name"),
                        row("first")
                ),
                JdbcResult.resultSet(
                        columns("second_name"),
                        row("second")
                ),
                JdbcResult.resultSet(
                        columns("third_name"),
                        row("third")
                )
        ));

        new DefaultSQLExecutor().execute(connection, "batch sql", headerList -> headers.add(headerNames(headerList)),
                row -> rows.add(new ArrayList<>(row)), JDBCDataValue::getString, false, 2);

        assertEquals(List.of(List.of("second_name")), headers);
        assertEquals(List.of(List.of("second")), rows);
    }

    @Test
    void exportSkipsLeadingUpdateCountsAndWritesSelectedResultSet() {
        List<List<String>> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        Connection connection = connection(statement(
                JdbcResult.update(0),
                JdbcResult.update(1),
                JdbcResult.resultSet(
                        columns("first_name"),
                        row("first")
                ),
                JdbcResult.update(2),
                JdbcResult.resultSet(
                        columns("second_name"),
                        row("second")
                )
        ));

        new DefaultSQLExecutor().execute(connection, "batch sql", headerList -> headers.add(headerNames(headerList)),
                row -> rows.add(new ArrayList<>(row)), JDBCDataValue::getString, false, 2);

        assertEquals(List.of(List.of("second_name")), headers);
        assertEquals(List.of(List.of("second")), rows);
    }

    private static Connection connection(Statement statement) {
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("createStatement".equals(method.getName()) || "prepareStatement".equals(method.getName())) {
                return statement;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static Statement statement(JdbcResult... results) {
        return proxy(PreparedStatement.class, new StatementHandler(Arrays.asList(results)));
    }

    private static ResultSet resultSet(List<String> columns, List<List<String>> rows) {
        return proxy(ResultSet.class, new ResultSetHandler(columns, rows));
    }

    private static ResultSetMetaData resultSetMetaData(List<String> columns) {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> {
            String name = method.getName();
            if ("getColumnCount".equals(name)) {
                return columns.size();
            }
            if ("getColumnLabel".equals(name) || "getColumnName".equals(name)) {
                return columns.get((Integer) args[0] - 1);
            }
            if ("getColumnTypeName".equals(name)) {
                return "VARCHAR";
            }
            if ("getColumnType".equals(name)) {
                return Types.VARCHAR;
            }
            if ("getTableName".equals(name)) {
                return "test_table";
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static List<String> headerNames(List<Header> headers) {
        return headers.stream().map(Header::getName).toList();
    }

    private static List<String> columns(String... columns) {
        return Arrays.asList(columns);
    }

    private static List<String> row(String... values) {
        return Arrays.asList(values);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(target, method, args);
            }
            if ("unwrap".equals(method.getName())) {
                return null;
            }
            if ("isWrapperFor".equals(method.getName())) {
                return false;
            }
            return handler.invoke(target, method, args);
        });
        return type.cast(proxy);
    }

    private static Object invokeObjectMethod(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> target.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class TestPlugin implements IPlugin {

        private final DBConfig dbConfig;

        private TestPlugin() {
            dbConfig = new DBConfig();
            dbConfig.setDbType(TEST_DB_TYPE);
        }

        @Override
        public DBConfig getDBConfig() {
            return dbConfig;
        }
    }

    private record JdbcResult(Integer updateCount, ResultSet resultSet) {
        static JdbcResult update(int updateCount) {
            return new JdbcResult(updateCount, null);
        }

        @SafeVarargs
        static JdbcResult resultSet(List<String> columns, List<String>... rows) {
            return new JdbcResult(null, DefaultSQLExecutorExportTest.resultSet(columns, Arrays.asList(rows)));
        }

        boolean isResultSet() {
            return resultSet != null;
        }
    }

    private static class StatementHandler implements InvocationHandler {
        private final List<JdbcResult> results;
        private int index = -1;
        private int maxRows;

        private StatementHandler(List<JdbcResult> results) {
            this.results = results;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "setMaxRows" -> {
                    maxRows = (Integer) args[0];
                    yield null;
                }
                case "execute" -> {
                    index = 0;
                    yield current().isResultSet();
                }
                case "getResultSet" -> current().resultSet();
                case "getUpdateCount" -> current() == null || current().isResultSet() ? -1 : current().updateCount();
                case "getMoreResults" -> {
                    index++;
                    yield current() != null && current().isResultSet();
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private JdbcResult current() {
            if (index < 0 || index >= results.size()) {
                return null;
            }
            return results.get(index);
        }
    }

    private static class ResultSetHandler implements InvocationHandler {
        private final List<String> columns;
        private final List<List<String>> rows;
        private int index = -1;

        private ResultSetHandler(List<String> columns, List<List<String>> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> ++index < rows.size();
                case "getMetaData" -> resultSetMetaData(columns);
                case "getObject", "getString" -> rows.get(index).get((Integer) args[0] - 1);
                default -> defaultValue(method.getReturnType());
            };
        }
    }
}
