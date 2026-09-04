package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.model.account.AccountGrant;
import ai.chat2db.community.domain.api.model.account.AccountGrantSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MysqlAccountManagerTest {

    @Test
    void showGrantsReadsBackDirectAndInheritedRoutineGrants() {
        AtomicReference<String> preparedSql = new AtomicReference<>();
        Connection connection = proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                preparedSql.set((String) args[0]);
                yield preparedStatement(List.of(
                        "GRANT EXECUTE ON FUNCTION `app`.`calculate_total` TO 'runner'@'%'",
                        "GRANT ALTER ROUTINE ON PROCEDURE `app`.`sync_orders` TO 'runner'@'%' WITH GRANT OPTION",
                        "GRANT EXECUTE ON `app`.* TO 'runner'@'%'"
                ));
            }
            default -> defaultValue(method.getReturnType());
        });

        List<String> grants = new MysqlAccountManager().showGrants(connection, "runner", "%");

        assertEquals("SHOW GRANTS FOR 'runner'@'%'", preparedSql.get());
        assertEquals(List.of(
                "GRANT EXECUTE ON FUNCTION `app`.`calculate_total` TO 'runner'@'%'",
                "GRANT ALTER ROUTINE ON PROCEDURE `app`.`sync_orders` TO 'runner'@'%' WITH GRANT OPTION",
                "GRANT EXECUTE ON `app`.* TO 'runner'@'%'"
        ), grants);
    }

    @Test
    void grantSummaryLabelsDirectRoutineDatabaseGlobalAndRoleSources() {
        Connection connection = proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> preparedStatement(List.of(
                    "GRANT EXECUTE ON FUNCTION `app``prod`.`calculate.total` TO 'runner'@'%'",
                    "GRANT ALTER ROUTINE ON PROCEDURE `app``prod`.`sync``orders` TO 'runner'@'%' WITH GRANT OPTION",
                    "GRANT EXECUTE ON `app``prod`.* TO 'runner'@'%'",
                    "GRANT ALL PRIVILEGES ON *.* TO 'runner'@'%' WITH GRANT OPTION",
                    "GRANT `routine_role`@`%` TO 'runner'@'%'"
            ));
            default -> defaultValue(method.getReturnType());
        });

        AccountGrantSummary summary = new MysqlAccountManager().grantSummary(connection, "runner", "%");

        assertEquals(Boolean.TRUE, summary.getReadable());
        assertEquals(5, summary.getRawStatements().size());
        assertEquals(5, summary.getGrants().size());

        AccountGrant functionGrant = summary.getGrants().get(0);
        assertEquals(MysqlGrantParser.SOURCE_DIRECT_ROUTINE, functionGrant.getSource());
        assertEquals("FUNCTION", functionGrant.getScope());
        assertEquals("app`prod", functionGrant.getDatabaseName());
        assertEquals("calculate.total", functionGrant.getObjectName());
        assertEquals(List.of("EXECUTE"), functionGrant.getPrivileges());
        assertEquals(Boolean.TRUE, functionGrant.getRevocable());

        AccountGrant procedureGrant = summary.getGrants().get(1);
        assertEquals(MysqlGrantParser.SOURCE_DIRECT_ROUTINE, procedureGrant.getSource());
        assertEquals("PROCEDURE", procedureGrant.getScope());
        assertEquals("sync`orders", procedureGrant.getObjectName());
        assertEquals(List.of("ALTER_ROUTINE"), procedureGrant.getPrivileges());
        assertEquals(Boolean.TRUE, procedureGrant.getGrantOption());

        AccountGrant databaseGrant = summary.getGrants().get(2);
        assertEquals(MysqlGrantParser.SOURCE_INHERITED_DATABASE, databaseGrant.getSource());
        assertEquals("app`prod", databaseGrant.getDatabaseName());
        assertEquals(Boolean.FALSE, databaseGrant.getRevocable());

        AccountGrant globalGrant = summary.getGrants().get(3);
        assertEquals(MysqlGrantParser.SOURCE_INHERITED_GLOBAL, globalGrant.getSource());
        assertEquals(List.of(MysqlGrantParser.PRIVILEGE_ALL), globalGrant.getPrivileges());
        assertEquals(Boolean.FALSE, globalGrant.getDirect());

        AccountGrant roleGrant = summary.getGrants().get(4);
        assertEquals(MysqlGrantParser.SOURCE_INHERITED_ROLE, roleGrant.getSource());
        assertEquals("`routine_role`@`%`", roleGrant.getRoleName());
        assertEquals(Boolean.FALSE, roleGrant.getRevocable());
    }

    @Test
    void grantSummaryDoesNotPromoteTableGrantOrUsageIntoRoutineAccess() {
        AccountGrantSummary summary = MysqlGrantParser.readable(List.of(
                "GRANT USAGE ON *.* TO 'runner'@'%'",
                "GRANT SELECT ON `app`.* TO 'runner'@'%'",
                "GRANT EXECUTE ON `app`.`jobs` TO 'runner'@'%'",
                "GRANT USAGE ON PROCEDURE `app`.`sync_orders` TO 'runner'@'%' WITH GRANT OPTION"
        ));

        assertEquals(4, summary.getRawStatements().size());
        assertEquals(4, summary.getGrants().size());
        summary.getGrants().forEach(
                grant -> assertEquals(MysqlGrantParser.SOURCE_UNPARSED, grant.getSource()));
    }

    @Test
    void grantSummaryReportsUnreadableShowGrantsWithoutThrowing() {
        Connection connection = proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> throw new SQLException("Access denied for SHOW GRANTS");
            default -> defaultValue(method.getReturnType());
        });

        AccountGrantSummary summary = new MysqlAccountManager().grantSummary(connection, "runner", "%");

        assertEquals(Boolean.FALSE, summary.getReadable());
        assertEquals("Access denied for SHOW GRANTS", summary.getMessage());
        assertEquals(List.of(), summary.getRawStatements());
        assertEquals(List.of(), summary.getGrants());
    }

    @Test
    void grantOptionIsParsedOnlyFromTrailingClauseOutsideAccountLiterals() {
        AccountGrantSummary summary = MysqlGrantParser.readable(List.of(
                "GRANT EXECUTE ON FUNCTION `app`.`run` TO 'WITH GRANT OPTION'@'%'",
                "GRANT EXECUTE ON PROCEDURE `app`.`sync` TO 'runner'@'%' WITH GRANT OPTION"
        ));

        assertEquals(Boolean.FALSE, summary.getGrants().get(0).getGrantOption());
        assertEquals(Boolean.TRUE, summary.getGrants().get(1).getGrantOption());
    }

    private static PreparedStatement preparedStatement(List<String> rows) {
        ResultSet resultSet = resultSet(rows);
        return proxy(PreparedStatement.class, (target, method, args) -> switch (method.getName()) {
            case "executeQuery" -> resultSet;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(List<String> rows) {
        AtomicReference<Integer> index = new AtomicReference<>(-1);
        return proxy(ResultSet.class, (target, method, args) -> switch (method.getName()) {
            case "next" -> {
                index.set(index.get() + 1);
                yield index.get() < rows.size();
            }
            case "getString" -> rows.get(index.get());
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == args[0];
                    default -> null;
                };
            }
            return handler.invoke(target, method, args);
        });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
