package ai.chat2db.plugin.opengauss;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.opengauss.constant.OpenGaussMetaDataConstants.TABLE_DDL_SQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenGaussMetaDataTest {

    @Test
    void tableDdlUsesTableOidAndRemovesSearchPath() {
        String nativeDdl = """
                SET search_path = public;
                CREATE TABLE app_user (id bigint);
                COMMENT ON TABLE app_user IS 'application users';
                COMMENT ON COLUMN app_user.id IS 'primary key';
                """;
        JdbcFixture fixture = new JdbcFixture(nativeDdl);

        String ddl = new OpenGaussMetaData().tableDDL(fixture.connection(), "app", "public", "app_user");

        assertEquals(TABLE_DDL_SQL, fixture.preparedSql);
        assertEquals(List.of("public", "app_user"), fixture.parameters);
        assertEquals("""
                CREATE TABLE app_user (id bigint);
                COMMENT ON TABLE app_user IS 'application users';
                COMMENT ON COLUMN app_user.id IS 'primary key';
                """, ddl);
    }

    @Test
    void stripLeadingSearchPathHandlesQuotedSemicolon() {
        String ddl = "SET search_path = \"tenant;one\";\r\nCREATE TABLE test_table (id bigint);";

        assertEquals("CREATE TABLE test_table (id bigint);", OpenGaussMetaData.stripLeadingSearchPath(ddl));
    }

    @Test
    void stripLeadingSearchPathLeavesOtherDdlUnchanged() {
        String ddl = "CREATE TABLE public.test_table (id bigint);";

        assertEquals(ddl, OpenGaussMetaData.stripLeadingSearchPath(ddl));
    }

    @Test
    void databasesKeepsPostgresAndMarksSystemDatabases() {
        // template0/template1 are already filtered by the PostgreSQL parent query
        List<String> rows = List.of("template0", "template1", "template2", "postgres", "appdb");
        Connection connection = connectionReturningDatnames(rows, "jdbc:opengauss://localhost:5432/postgres");

        List<Database> databases = new OpenGaussMetaData().databases(connection);

        Map<String, Database> byName = databases.stream()
                .collect(Collectors.toMap(Database::getName, Function.identity()));
        assertEquals(3, byName.size(), "system databases must be kept, not removed: " + byName.keySet());
        assertTrue(byName.get("postgres").isSystem());
        assertTrue(byName.get("template2").isSystem());
        assertFalse(byName.get("appdb").isSystem());
    }

    @Test
    void schemasKeepSystemSchemasAndMarkThem() {
        List<String> rows = List.of("public", "snapshot", "pg_catalog", "information_schema");
        Connection connection = connectionReturningSchemaRows(rows);

        List<Schema> schemas = new OpenGaussMetaData().schemas(connection, "postgres");

        Map<String, Schema> byName = schemas.stream()
                .collect(Collectors.toMap(Schema::getName, Function.identity()));
        assertEquals(4, byName.size(), "system schemas must be kept, not removed: " + byName.keySet());
        assertTrue(byName.get("snapshot").isSystem());
        assertTrue(byName.get("pg_catalog").isSystem());
        assertTrue(byName.get("information_schema").isSystem());
        assertFalse(byName.get("public").isSystem());
    }

    private static Connection connectionReturningDatnames(List<String> rows, String url) {
        ResultSet resultSet = rowIteratingResultSet(rows);
        PreparedStatement statement = proxy(PreparedStatement.class, (p, method, args) -> switch (method.getName()) {
            case "execute" -> true;
            case "getResultSet" -> resultSet;
            default -> defaultValue(method.getReturnType());
        });
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (p, method, args) ->
                "getURL".equals(method.getName()) ? url : defaultValue(method.getReturnType()));
        return proxy(Connection.class, (p, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement;
            case "getMetaData" -> metaData;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Connection connectionReturningSchemaRows(List<String> rows) {
        ResultSetMetaData rsMetaData = proxy(ResultSetMetaData.class, (p, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> 1;
            case "getColumnLabel", "getColumnName" -> "TABLE_SCHEM";
            default -> defaultValue(method.getReturnType());
        });
        Iterator<String> iterator = rows.iterator();
        String[] current = new String[1];
        ResultSet resultSet = proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
            case "next" -> {
                boolean hasNext = iterator.hasNext();
                if (hasNext) {
                    current[0] = iterator.next();
                }
                yield hasNext;
            }
            case "getObject" -> current[0];
            case "getMetaData" -> rsMetaData;
            default -> defaultValue(method.getReturnType());
        });
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (p, method, args) ->
                "getSchemas".equals(method.getName()) ? resultSet : defaultValue(method.getReturnType()));
        return proxy(Connection.class, (p, method, args) ->
                "getMetaData".equals(method.getName()) ? metaData : defaultValue(method.getReturnType()));
    }

    private static ResultSet rowIteratingResultSet(List<String> rows) {
        Iterator<String> iterator = rows.iterator();
        String[] current = new String[1];
        return proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
            case "next" -> {
                boolean hasNext = iterator.hasNext();
                if (hasNext) {
                    current[0] = iterator.next();
                }
                yield hasNext;
            }
            case "getString" -> current[0];
            default -> defaultValue(method.getReturnType());
        });
    }

    private static final class JdbcFixture {
        private final String ddl;
        private final List<String> parameters = new ArrayList<>();
        private String preparedSql;
        private boolean resultRead;

        private JdbcFixture(String ddl) {
            this.ddl = ddl;
        }

        private Connection connection() {
            ResultSet resultSet = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> !resultRead && (resultRead = true);
                case "getString" -> ddl;
                default -> defaultValue(method.getReturnType());
            });
            PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setString" -> {
                        parameters.add((String) args[1]);
                        return null;
                    }
                    case "execute" -> {
                        return true;
                    }
                    case "getResultSet" -> {
                        return resultSet;
                    }
                    default -> {
                        return defaultValue(method.getReturnType());
                    }
                }
            });
            return proxy(Connection.class, (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    preparedSql = (String) args[0];
                    return statement;
                }
                return defaultValue(method.getReturnType());
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
