package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.exception.ai.AiToolInvalidArgumentException;
import ai.chat2db.community.domain.api.exception.ai.AiToolMetadataQueryException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlConfirmationRequiredException;
import ai.chat2db.community.domain.api.exception.ai.AiToolSqlExecutionException;
import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseService;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiToolServiceImplTest {

    @Test
    void executeSqlRejectsBlankSqlAsStrongException() {
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql(" ");

        assertThrows(AiToolInvalidArgumentException.class,
                () -> new AiToolServiceImpl().executeSql(request));
    }

    @Test
    void getTablesSchemaRejectsEmptyTableNamesAsStrongException() {
        AiGetTablesSchemaRequest request = new AiGetTablesSchemaRequest();
        request.setTableNames(List.of());

        assertThrows(AiToolInvalidArgumentException.class,
                () -> new AiToolServiceImpl().getTablesSchema(request));
    }

    @Test
    void dtoRequestMethodsRejectNullRequestAsInvalidArgument() {
        AiToolServiceImpl service = new AiToolServiceImpl();

        AiToolInvalidArgumentException listTablesException = assertThrows(
                AiToolInvalidArgumentException.class,
                () -> service.listAllTables(null));
        AiToolInvalidArgumentException executeSqlException = assertThrows(
                AiToolInvalidArgumentException.class,
                () -> service.executeSql(null));
        AiToolInvalidArgumentException tableSchemaException = assertThrows(
                AiToolInvalidArgumentException.class,
                () -> service.getTablesSchema(null));

        assertEquals("listAllTables request is required.", listTablesException.getMessage());
        assertEquals("executeSql request is required.", executeSqlException.getMessage());
        assertEquals("getTablesSchema request is required.", tableSchemaException.getMessage());
    }

    @Test
    void listAllTablesReturnsStrongTypedTablesAndClearsConnectionContext() {
        Fixture fixture = new Fixture();
        SimpleTable table = SimpleTable.builder()
                .name("orders")
                .tableType("BASE TABLE")
                .comment("business orders")
                .build();
        inject(fixture.service, "connectionContextService", fixture.connectionContextService());
        inject(fixture.service, "tableService", proxy(IDbTableService.class, (proxy, method, args) -> {
            if ("queryTables".equals(method.getName())) {
                return List.of(table);
            }
            return defaultValue(method.getReturnType());
        }));

        AiListTablesRequest request = new AiListTablesRequest();
        request.setDataSourceId(1L);
        request.setDatabaseName("app");

        List<SimpleTable> result = fixture.service.listAllTables(request);

        assertEquals(1, result.size());
        assertSame(table, result.get(0));
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
    }

    @Test
    void listAllDatabasesReturnsStrongTypedDatabasesAndClearsConnectionContext() {
        Fixture fixture = new Fixture();
        Database database = Database.builder()
                .name("app")
                .comment("application database")
                .build();
        inject(fixture.service, "connectionContextService", fixture.connectionContextService());
        inject(fixture.service, "databaseService", proxy(IDbDatabaseService.class, (proxy, method, args) -> {
            if ("queryAll".equals(method.getName())) {
                return List.of(database);
            }
            return defaultValue(method.getReturnType());
        }));

        List<Database> result = fixture.service.listAllDatabases(1L, null);

        assertEquals(1, result.size());
        assertSame(database, result.get(0));
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
    }

    @Test
    void getTablesSchemaReturnsStrongTypedUseCaseResultAndClearsConnectionContext() {
        Fixture fixture = new Fixture();
        Table table = Table.builder()
                .name("orders")
                .databaseName("app")
                .build();
        inject(fixture.service, "connectionContextService", fixture.connectionContextService());
        inject(fixture.service, "tableService", tableService(table, "create table orders(id bigint)", null));

        List<TableSchemaResult> result = fixture.service.getTablesSchema(tablesSchemaRequest("orders"));

        assertEquals(1, result.size());
        assertEquals("orders", result.get(0).getTableName());
        assertEquals("create table orders(id bigint)", result.get(0).getDdl());
        assertSame(table, result.get(0).getTable());
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
    }

    @Test
    void getTablesSchemaThrowsMetadataExceptionAndClearsConnectionContextWhenMetadataQueryFails() {
        Fixture fixture = new Fixture();
        inject(fixture.service, "connectionContextService", fixture.connectionContextService());
        inject(fixture.service, "tableService", tableService(null, null, new BusinessException("metadata failed")));

        AiToolMetadataQueryException exception = assertThrows(
                AiToolMetadataQueryException.class,
                () -> fixture.service.getTablesSchema(tablesSchemaRequest("orders")));

        assertEquals(true, exception.getMessage().contains("metadata failed"));
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
    }

    @Test
    void getTablesSchemaPropagatesUnexpectedRuntimeExceptionAndClearsConnectionContext() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("bug failed");
        inject(fixture.service, "connectionContextService", fixture.connectionContextService());
        inject(fixture.service, "tableService", tableService(null, null, failure));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> fixture.service.getTablesSchema(tablesSchemaRequest("orders")));

        assertSame(failure, exception);
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
    }

    @Test
    void executeSqlReturnsStrongTypedResultCleansConnectionContextAndRecordsOperationLog() {
        Fixture fixture = new Fixture();
        ExecuteResponse response = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .sqlType("SELECT")
                .message("ok")
                .build();
        injectExecuteDependencies(fixture, List.of(response), null);

        List<ExecuteResponse> result = fixture.service.executeSql(executeRequest("select 1"));

        assertEquals(1, result.size());
        assertSame(response, result.get(0));
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
        assertEquals(1, fixture.recordListResultCalls.get());
        assertEquals(0, fixture.recordFailureCalls.get());
    }

    @Test
    void executeSqlRunsProfileBindExecutionAndLogInsideToolRequestContext() {
        Fixture fixture = new Fixture();
        Context requestContext = Context.builder()
                .organizationId(99L)
                .token("request-token")
                .build();
        AiToolContextRequest toolContext = new AiToolContextRequest();
        toolContext.setDataSourceId(1L);
        toolContext.setDatabaseName("app");
        toolContext.setRequestContext(requestContext);
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql("select 1");
        request.setAiToolContextRequest(toolContext);
        ExecuteResponse response = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .sqlType("SELECT")
                .build();
        injectExecuteDependencies(fixture, List.of(response), null);

        List<ExecuteResponse> result = fixture.service.executeSql(request);

        assertEquals(1, result.size());
        assertEquals(1, fixture.buildProfileCalls.get());
        assertSame(requestContext, fixture.buildProfileContext);
        assertSame(requestContext, fixture.bindProfileContext);
        assertSame(requestContext, fixture.executeContext);
        assertSame(requestContext, fixture.recordListResultContext);
        assertNull(ContextUtils.queryContext());
    }

    @Test
    void executeSqlThrowsConfirmationExceptionBeforeBindingOrLoggingNonQuerySql() {
        Fixture fixture = new Fixture();
        inject(fixture.service, "connectionContextService", fixture.connectionContextService());
        inject(fixture.service, "sqlService", sqlService("UPDATE"));
        inject(fixture.service, "sqlOperationLogRecorder", fixture.sqlOperationLogService());

        AiToolSqlConfirmationRequiredException exception = assertThrows(
                AiToolSqlConfirmationRequiredException.class,
                () -> fixture.service.executeSql(executeRequest("update users set name = 'x'")));

        assertEquals(true, exception.getMessage().contains("Non-query SQL cannot be auto-executed"));
        assertEquals(0, fixture.bindProfileCalls.get());
        assertEquals(0, fixture.clearCalls.get());
        assertEquals(0, fixture.recordListResultCalls.get());
        assertEquals(0, fixture.recordFailureCalls.get());
    }

    @Test
    void executeSqlThrowsExecutionExceptionRecordsFailureAndClearsContextWhenExecutorFails() {
        Fixture fixture = new Fixture();
        injectExecuteDependencies(fixture, null, new BusinessException("driver failed"));

        AiToolSqlExecutionException exception = assertThrows(
                AiToolSqlExecutionException.class,
                () -> fixture.service.executeSql(executeRequest("select 1")));

        assertEquals(true, exception.getMessage().contains("driver failed"));
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
        assertEquals(0, fixture.recordListResultCalls.get());
        assertEquals(1, fixture.recordFailureCalls.get());
    }

    @Test
    void executeSqlPropagatesUnexpectedRuntimeExceptionRecordsFailureAndClearsContext() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("driver bug");
        injectExecuteDependencies(fixture, null, failure);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> fixture.service.executeSql(executeRequest("select 1")));

        assertSame(failure, exception);
        assertEquals(1, fixture.bindProfileCalls.get());
        assertEquals(1, fixture.clearCalls.get());
        assertEquals(0, fixture.recordListResultCalls.get());
        assertEquals(1, fixture.recordFailureCalls.get());
    }

    @Test
    void executeSqlThrowsExecutionExceptionAndRecordsListResultWhenResultSetReportsFailure() {
        Fixture fixture = new Fixture();
        ExecuteResponse failed = ExecuteResponse.builder()
                .success(Boolean.FALSE)
                .message("syntax error")
                .description("bad sql")
                .build();
        injectExecuteDependencies(fixture, List.of(failed), null);

        AiToolSqlExecutionException exception = assertThrows(
                AiToolSqlExecutionException.class,
                () -> fixture.service.executeSql(executeRequest("select broken")));

        assertEquals(true, exception.getMessage().contains("syntax error"));
        assertEquals(1, fixture.clearCalls.get());
        assertEquals(1, fixture.recordListResultCalls.get());
        assertEquals(0, fixture.recordFailureCalls.get());
    }

    private static void injectExecuteDependencies(Fixture fixture, List<ExecuteResponse> responses, RuntimeException failure) {
        inject(fixture.service, "connectionContextService", fixture.connectionContextService());
        inject(fixture.service, "sqlService", sqlService("SELECT"));
        inject(fixture.service, "sqlOperationLogRecorder", fixture.sqlOperationLogService());
        inject(fixture.service, "dlTemplateService", proxy(IDbDlTemplateService.class, (proxy, method, args) -> {
            if ("execute".equals(method.getName())) {
                fixture.executedSql = ((DbDlExecuteRequest) args[0]).getSql();
                fixture.executeContext = ContextUtils.queryContext();
                if (failure != null) {
                    throw failure;
                }
                return responses;
            }
            return defaultValue(method.getReturnType());
        }));
    }

    private static IDbSqlService sqlService(String sqlType) {
        return proxy(IDbSqlService.class, (proxy, method, args) -> {
            if ("parseStatements".equals(method.getName())) {
                SimpleSqlStatement statement = new SimpleSqlStatement();
                statement.setSql((String) args[0]);
                statement.setSqlType(sqlType);
                return List.of(statement);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static AiExecuteSqlRequest executeRequest(String sql) {
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql(sql);
        request.setDataSourceId(1L);
        request.setDatabaseName("app");
        return request;
    }

    private static AiGetTablesSchemaRequest tablesSchemaRequest(String tableName) {
        AiGetTablesSchemaRequest request = new AiGetTablesSchemaRequest();
        request.setTableNames(List.of(tableName));
        request.setDataSourceId(1L);
        request.setDatabaseName("app");
        return request;
    }

    private static IDbTableService tableService(Table table, String ddl, RuntimeException failure) {
        return proxy(IDbTableService.class, (proxy, method, args) -> {
            if ("query".equals(method.getName())) {
                if (failure != null) {
                    throw failure;
                }
                return table;
            }
            if ("showCreateTable".equals(method.getName())) {
                return ddl;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (List.class.isAssignableFrom(returnType)) {
            return Collections.emptyList();
        }
        return null;
    }

    private static class Fixture {
        private final AiToolServiceImpl service = new AiToolServiceImpl();
        private final AtomicInteger buildProfileCalls = new AtomicInteger();
        private final AtomicInteger bindProfileCalls = new AtomicInteger();
        private final AtomicInteger clearCalls = new AtomicInteger();
        private final AtomicInteger recordListResultCalls = new AtomicInteger();
        private final AtomicInteger recordFailureCalls = new AtomicInteger();
        private String executedSql;
        private Context buildProfileContext;
        private Context bindProfileContext;
        private Context executeContext;
        private Context recordListResultContext;
        private Context recordFailureContext;

        private IDbConnectionContextService connectionContextService() {
            return proxy(IDbConnectionContextService.class, (proxy, method, args) -> {
                if ("buildProfile".equals(method.getName())) {
                    buildProfileCalls.incrementAndGet();
                    buildProfileContext = ContextUtils.queryContext();
                    ConnectionProfile profile = new ConnectionProfile();
                    profile.setDataSourceId(1L);
                    profile.setDatabaseName("app");
                    profile.setDbType("MYSQL");
                    return profile;
                }
                if ("bindProfile".equals(method.getName())) {
                    bindProfileContext = ContextUtils.queryContext();
                    bindProfileCalls.incrementAndGet();
                    return null;
                }
                if ("clear".equals(method.getName())) {
                    clearCalls.incrementAndGet();
                    return null;
                }
                return defaultValue(method.getReturnType());
            });
        }

        private IOpsSqlOperationLogService sqlOperationLogService() {
            return proxy(IOpsSqlOperationLogService.class, (proxy, method, args) -> {
                if ("recordListResultAsync".equals(method.getName())) {
                    recordListResultContext = ContextUtils.queryContext();
                    recordListResultCalls.incrementAndGet();
                    return null;
                }
                if ("recordFailureAsync".equals(method.getName())) {
                    recordFailureContext = ContextUtils.queryContext();
                    recordFailureCalls.incrementAndGet();
                    return null;
                }
                return defaultValue(method.getReturnType());
            });
        }
    }
}
