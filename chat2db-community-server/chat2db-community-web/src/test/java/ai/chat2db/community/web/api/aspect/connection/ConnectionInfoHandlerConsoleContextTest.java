package ai.chat2db.community.web.api.aspect.connection;

import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.web.api.controller.DbVariableController;
import ai.chat2db.community.web.api.model.request.db.DdlExecuteRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionInfoHandlerConsoleContextTest {

    @Test
    void variableRequestPreservesConsoleId() throws Throwable {
        DbVariableController.VariableListRequest request = new DbVariableController.VariableListRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setConsoleId(101L);

        DbConnectionContextRequest bound = invoke(request);

        assertEquals(7L, bound.getDataSourceId());
        assertEquals("app", bound.getDatabaseName());
        assertEquals("public", bound.getSchemaName());
        assertEquals(101L, bound.getConsoleId());
    }

    @Test
    void ddlRequestPreservesConsoleAndSchemaContext() throws Throwable {
        DdlExecuteRequest request = new DdlExecuteRequest();
        request.setDataSourceId(8L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setConsoleId(202L);

        DbConnectionContextRequest bound = invoke(request);

        assertEquals(8L, bound.getDataSourceId());
        assertEquals("app", bound.getDatabaseName());
        assertEquals("public", bound.getSchemaName());
        assertEquals(202L, bound.getConsoleId());
    }

    private DbConnectionContextRequest invoke(Object request) throws Throwable {
        AtomicReference<DbConnectionContextRequest> captured = new AtomicReference<>();
        IDbConnectionContextService service = (IDbConnectionContextService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{IDbConnectionContextService.class},
                (proxy, method, args) -> {
                    if ("bind".equals(method.getName())) {
                        captured.set((DbConnectionContextRequest) args[0]);
                    }
                    return null;
                });
        ConnectionInfoHandler handler = new ConnectionInfoHandler();
        Field field = ConnectionInfoHandler.class.getDeclaredField("connectionContextService");
        field.setAccessible(true);
        field.set(handler, service);
        ProceedingJoinPoint joinPoint = (ProceedingJoinPoint) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getArgs" -> new Object[]{request};
                    case "proceed" -> null;
                    default -> null;
                });

        handler.connectionInfoHandler(joinPoint);
        return captured.get();
    }
}
