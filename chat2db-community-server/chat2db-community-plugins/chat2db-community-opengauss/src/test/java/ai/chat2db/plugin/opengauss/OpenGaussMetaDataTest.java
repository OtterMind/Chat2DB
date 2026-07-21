package ai.chat2db.plugin.opengauss;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static ai.chat2db.plugin.opengauss.constant.OpenGaussMetaDataConstants.TABLE_DDL_SQL;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
