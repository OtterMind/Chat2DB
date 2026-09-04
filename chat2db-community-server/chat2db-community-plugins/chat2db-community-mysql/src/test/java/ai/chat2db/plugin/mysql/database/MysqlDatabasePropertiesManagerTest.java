package ai.chat2db.plugin.mysql.database;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlDatabasePropertiesManagerTest {

    private final MysqlDatabasePropertiesManager manager = new MysqlDatabasePropertiesManager();

    @Test
    void databaseInfoBindsDatabaseNameInsteadOfConcatenatingIt() {
        RecordingJdbc jdbc = new RecordingJdbc();

        Map<String, String> result = manager.databaseInfo(jdbc.connection(), "app'; DROP DATABASE mysql; --");

        assertEquals("utf8mb4", result.get("charset"));
        assertEquals("utf8mb4_0900_ai_ci", result.get("collation"));
        assertFalse(jdbc.preparedSql.contains("app'; DROP DATABASE mysql; --"));
        assertEquals("app'; DROP DATABASE mysql; --", jdbc.boundDatabaseName);
    }

    @Test
    void databaseInfoUsesVersionStableInformationSchemaReadback() {
        RecordingJdbc jdbc = new RecordingJdbc();

        manager.databaseInfo(jdbc.connection(), "app");

        assertEquals("SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                + "FROM information_schema.schemata WHERE SCHEMA_NAME = ?", jdbc.preparedSql);
        assertEquals("app", jdbc.boundDatabaseName);
    }

    @Test
    void databaseInfoReportsPrivilegeFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.failQueries(new SQLException("SELECT command denied to user", "42000", 1142));

        assertThrows(BusinessException.class, () -> manager.databaseInfo(jdbc.connection(), "app"));
    }

    @Test
    void previewReportsReadbackPrivilegeFailure() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.failQueries(new SQLException("SELECT command denied to user", "42000", 1142));

        assertThrows(BusinessException.class,
                () -> manager.previewAlterDatabaseSql(
                        jdbc.connection(), "app", "utf8mb4", "utf8mb4_bin"));
    }

    @Test
    void previewRejectsUnsafeOrIncompatibleOptions() {
        RecordingJdbc jdbc = new RecordingJdbc();
        Connection connection = jdbc.connection();

        assertThrows(BusinessException.class,
                () -> manager.previewAlterDatabaseSql(
                        connection, "app", "utf8mb4;DROP DATABASE mysql", null));
        assertThrows(BusinessException.class,
                () -> manager.previewAlterDatabaseSql(
                        connection, "app", "utf8mb4", "utf8mb4_0900_ai_ci;DROP"));
        assertThrows(BusinessException.class,
                () -> manager.previewAlterDatabaseSql(connection, "app", "latin1", "utf8mb4_bin"));
        assertThrows(BusinessException.class,
                () -> manager.previewAlterDatabaseSql(connection, "app", null, "utf8_general_ci"));
    }

    @Test
    void previewQuotesDatabaseNameAndKeepsSafeOptions() {
        RecordingJdbc jdbc = new RecordingJdbc();

        String sql = manager.previewAlterDatabaseSql(
                jdbc.connection(), "app-db", "utf8mb4", "utf8mb4_bin");

        assertEquals("ALTER DATABASE `app-db` DEFAULT COLLATE utf8mb4_bin", sql);
    }

    @Test
    void previewReturnsNullWhenPropertiesAreUnchangedOrMissing() {
        Connection connection = new RecordingJdbc().connection();

        assertNull(manager.previewAlterDatabaseSql(connection, "app", null, null));
        assertNull(manager.previewAlterDatabaseSql(connection, "app", "utf8mb4", null));
        assertNull(manager.previewAlterDatabaseSql(connection, "app", null, "utf8mb4_0900_ai_ci"));
        assertNull(manager.previewAlterDatabaseSql(
                connection, "app", "utf8mb4", "utf8mb4_0900_ai_ci"));
    }

    @Test
    void previewIncludesOnlyPropertiesThatActuallyChange() {
        Connection connection = new RecordingJdbc().connection();

        assertEquals("ALTER DATABASE `app` DEFAULT COLLATE utf8mb4_bin",
                manager.previewAlterDatabaseSql(connection, "app", null, "utf8mb4_bin"));
        assertEquals("ALTER DATABASE `app` DEFAULT CHARACTER SET latin1",
                manager.previewAlterDatabaseSql(connection, "app", "latin1", null));
    }

    private static final class RecordingJdbc {

        private String preparedSql;
        private String boundDatabaseName;
        private SQLException executeQueryFailure;

        private void failQueries(SQLException failure) {
            executeQueryFailure = failure;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            preparedSql = (String) args[0];
                            yield preparedStatement();
                        }
                        case "isClosed" -> false;
                        case "close" -> null;
                        default -> null;
                    });
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setString" -> {
                            boundDatabaseName = (String) args[1];
                            yield null;
                        }
                        case "executeQuery" -> {
                            if (executeQueryFailure != null) {
                                throw executeQueryFailure;
                            }
                            yield resultSet();
                        }
                        case "close" -> null;
                        default -> null;
                    });
        }

        private ResultSet resultSet() {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> true;
                        case "getString" -> "DEFAULT_CHARACTER_SET_NAME".equals(args[0])
                                ? "utf8mb4" : "utf8mb4_0900_ai_ci";
                        case "close" -> null;
                        default -> null;
                    });
        }
    }
}
