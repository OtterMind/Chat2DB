package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolServiceImplTest {

    private static final String CTE_DELETE =
            "WITH doomed AS (SELECT * FROM users WHERE id = 1) DELETE FROM doomed";
    private static final String CTE_SELECT =
            "WITH active AS (SELECT * FROM users) SELECT * FROM active";

    @Test
    void parserFailureDoesNotAutoExecuteWithPrefixedDml() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AiToolServiceImpl service = service((proxy, method, args) -> {
            if ("parseStatements".equals(method.getName())) {
                throw new IllegalStateException("simulated parser failure");
            }
            return defaultValue(method.getReturnType());
        }, executions);

        String result = service.executeSql(request(CTE_DELETE));

        assertEquals(0, executions.get());
        assertTrue(result.contains("requires manual confirmation"), result);
    }

    @Test
    void emptyParserResultDoesNotAutoExecuteWithPrefixedDml() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AiToolServiceImpl service = service((proxy, method, args) ->
                "parseStatements".equals(method.getName())
                        ? List.of() : defaultValue(method.getReturnType()), executions);

        String result = service.executeSql(request(CTE_DELETE));

        assertEquals(0, executions.get());
        assertTrue(result.contains("requires manual confirmation"), result);
    }

    @Test
    void parserFailureDoesNotAutoExecuteTrailingStatements() throws Exception {
        for (String sql : List.of(
                "SELECT 1; DELETE FROM users",
                "SHOW TABLES; DROP TABLE users",
                "DESC users; UPDATE users SET name = 'changed'")) {
            AtomicInteger executions = new AtomicInteger();
            AiToolServiceImpl service = parserFailingService(executions);

            String result = service.executeSql(request(sql));

            assertEquals(0, executions.get(), sql);
            assertTrue(result.contains("requires manual confirmation"), result);
        }
    }

    @Test
    void parserFailureIgnoresSemicolonsInsideLiteralsAndComments() throws Exception {
        for (String sql : List.of(
                "SELECT ';' AS marker",
                "SELECT 1 -- ; DELETE FROM users\n",
                "SELECT /* ; DELETE FROM users */ 1",
                "SELECT 1;")) {
            AtomicInteger executions = new AtomicInteger();
            AiToolServiceImpl service = parserFailingService(executions);

            String result = service.executeSql(request(sql));

            assertEquals(1, executions.get(), sql);
            assertTrue(result.contains("executed successfully"), result);
        }
    }

    @Test
    void parserFailureDoesNotAutoExecuteSqlServerGoBatches() throws Exception {
        for (String sql : List.of(
                "SELECT 1\nGO\nDELETE FROM users",
                "SELECT 1;GO\nDELETE FROM users",
                "SELECT 1\nGO 2\nDELETE FROM users")) {
            AtomicInteger executions = new AtomicInteger();
            AiToolServiceImpl service = parserFailingService(executions, "SQLSERVER");

            String result = service.executeSql(request(sql));

            assertEquals(0, executions.get(), sql);
            assertTrue(result.contains("requires manual confirmation"), result);
        }
    }

    @Test
    void parserFailureIgnoresGoInsideSqlServerLiteralsAndComments() throws Exception {
        for (String sql : List.of(
                "SELECT 'first\nGO\nsecond' AS marker",
                "SELECT 1\n-- GO\n",
                "SELECT 1\n/*\nGO\n*/",
                "SELECT 1\nGO\n",
                "SELECT 1;GO\n")) {
            AtomicInteger executions = new AtomicInteger();
            AiToolServiceImpl service = parserFailingService(executions, "SQLSERVER");

            String result = service.executeSql(request(sql));

            assertEquals(1, executions.get(), sql);
            assertTrue(result.contains("executed successfully"), result);
        }
    }

    @Test
    void parserConfirmedWithSelectRemainsAutoExecutable() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        SimpleSqlStatement statement = new SimpleSqlStatement();
        statement.setSql(CTE_SELECT);
        statement.setSqlType("SELECT");
        AiToolServiceImpl service = service((proxy, method, args) ->
                "parseStatements".equals(method.getName())
                        ? List.of(statement) : defaultValue(method.getReturnType()), executions);

        String result = service.executeSql(request(CTE_SELECT));

        assertEquals(1, executions.get());
        assertTrue(result.contains("executed successfully"), result);
    }

    private static AiToolServiceImpl service(java.lang.reflect.InvocationHandler sqlHandler,
            AtomicInteger executions) throws Exception {
        return service(sqlHandler, executions, "MYSQL");
    }

    private static AiToolServiceImpl service(java.lang.reflect.InvocationHandler sqlHandler,
            AtomicInteger executions, String dbType) throws Exception {
        AiToolServiceImpl service = new AiToolServiceImpl();
        ConnectionProfile profile = new ConnectionProfile();
        profile.setDataSourceId(7L);
        profile.setDbType(dbType);

        setField(service, "sqlService", proxy(IDbSqlService.class, sqlHandler));
        setField(service, "connectionContextService", proxy(IDbConnectionContextService.class,
                (proxy, method, args) -> "buildProfile".equals(method.getName())
                        ? profile : defaultValue(method.getReturnType())));
        setField(service, "dlTemplateService", proxy(IDbDlTemplateService.class,
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        executions.incrementAndGet();
                        return List.of();
                    }
                    return defaultValue(method.getReturnType());
                }));
        setField(service, "sqlOperationLogRecorder", proxy(IOpsSqlOperationLogService.class,
                (proxy, method, args) -> defaultValue(method.getReturnType())));
        setField(service, "aiSqlAutoExecutionPolicy", new DefaultAiSqlAutoExecutionPolicy());
        return service;
    }

    private static AiToolServiceImpl parserFailingService(AtomicInteger executions) throws Exception {
        return parserFailingService(executions, "MYSQL");
    }

    private static AiToolServiceImpl parserFailingService(AtomicInteger executions, String dbType) throws Exception {
        return service((proxy, method, args) -> {
            if ("parseStatements".equals(method.getName())) {
                throw new IllegalStateException("simulated parser failure");
            }
            return defaultValue(method.getReturnType());
        }, executions, dbType);
    }

    private static AiExecuteSqlRequest request(String sql) {
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql(sql);
        request.setDataSourceId(7L);
        return request;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
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
