package ai.chat2db.community.web.api.aspect.connection;

import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionInfoHandlerLoggingTest {

    @Test
    void logsWhenCustomConnectionResolutionFails() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ConnectionInfoHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ApplicationContext original = ApplicationContextUtil.getApplicationContext();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("failingCustomConnection", ICustomConnection.class,
                    () -> (datasourceId, databaseName, schemaName, consolerId) -> {
                        throw new IllegalStateException("boom");
                    });
            context.refresh();
            new ApplicationContextUtil().setApplicationContext(context);

            Method method = ConnectionInfoHandler.class.getDeclaredMethod(
                    "customConnectionInfo", Long.class, String.class, Long.class, String.class);
            method.setAccessible(true);
            method.invoke(new ConnectionInfoHandler(), 1L, "db", null, null);

            assertTrue(appender.list.stream().anyMatch(event ->
                            event.getLevel() == Level.WARN
                                    && event.getFormattedMessage().contains("custom connection")),
                    "failures from custom connection resolution must be logged, not swallowed");
        } finally {
            logger.detachAppender(appender);
            new ApplicationContextUtil().setApplicationContext(original);
        }
    }

    @Test
    void clearsConnectionContextWhenProceededControllerThrows() throws Throwable {
        AtomicInteger clears = new AtomicInteger();
        AtomicReference<DbConnectionContextRequest> bound = new AtomicReference<>();
        ConnectionInfoHandler handler = new ConnectionInfoHandler();
        setConnectionContextService(handler, proxyConnectionContextService(bound, clears));
        DataSourceBaseRequest request = new DataSourceBaseRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("request_db");
        request.setSchemaName("request_schema");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> handler.connectionInfoHandler(joinPoint(new Object[]{request}, () -> {
                    assertEquals("request_db", bound.get().getDatabaseName());
                    throw new IllegalStateException("boom");
                })));

        assertEquals("boom", thrown.getMessage());
        assertEquals(1, clears.get());
    }

    @Test
    void bindsSqlEditorRequestWithConsoleAndSchemaContext() throws Throwable {
        AtomicInteger clears = new AtomicInteger();
        AtomicInteger locks = new AtomicInteger();
        AtomicReference<DbConnectionContextRequest> bound = new AtomicReference<>();
        ConnectionInfoHandler handler = new ConnectionInfoHandler();
        setConnectionContextService(handler, proxyConnectionContextService(bound, clears, false, locks));
        SqlEditorExecuteRequest request = new SqlEditorExecuteRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("request_db");
        request.setSchemaName("request_schema");
        request.setConsoleId(84L);

        Object result = handler.connectionInfoHandler(joinPoint(new Object[]{request}, () -> "executed"));

        assertEquals("executed", result);
        assertEquals(42L, bound.get().getDataSourceId());
        assertEquals("request_db", bound.get().getDatabaseName());
        assertEquals("request_schema", bound.get().getSchemaName());
        assertEquals(84L, bound.get().getConsoleId());
        assertEquals(1, clears.get());
        assertEquals(1, locks.get());
    }

    @Test
    void wrapsBoundConsoleExecutionInTransactionLock() throws Throwable {
        AtomicInteger clears = new AtomicInteger();
        AtomicInteger locks = new AtomicInteger();
        AtomicReference<DbConnectionContextRequest> bound = new AtomicReference<>();
        ConnectionInfoHandler handler = new ConnectionInfoHandler();
        setConnectionContextService(handler, proxyConnectionContextService(bound, clears, true, locks));
        SqlEditorExecuteRequest request = new SqlEditorExecuteRequest();
        request.setDataSourceId(42L);
        request.setConsoleId(84L);

        Object result = handler.connectionInfoHandler(joinPoint(new Object[]{request}, () -> {
            assertEquals(1, locks.get());
            assertEquals(84L, bound.get().getConsoleId());
            return "executed";
        }));

        assertEquals("executed", result);
        assertEquals(1, locks.get());
        assertEquals(1, clears.get());
    }

    private static void setConnectionContextService(ConnectionInfoHandler handler,
            IDbConnectionContextService service) throws Exception {
        Field field = ConnectionInfoHandler.class.getDeclaredField("connectionContextService");
        field.setAccessible(true);
        field.set(handler, service);
    }

    private static IDbConnectionContextService proxyConnectionContextService(
            AtomicReference<DbConnectionContextRequest> bound, AtomicInteger clears) {
        return proxyConnectionContextService(bound, clears, false, new AtomicInteger());
    }

    private static IDbConnectionContextService proxyConnectionContextService(
            AtomicReference<DbConnectionContextRequest> bound,
            AtomicInteger clears,
            boolean inTransaction,
            AtomicInteger locks
    ) {
        return (IDbConnectionContextService) Proxy.newProxyInstance(
                ConnectionInfoHandlerLoggingTest.class.getClassLoader(),
                new Class<?>[]{IDbConnectionContextService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "bind" -> {
                        bound.set((DbConnectionContextRequest) args[0]);
                        yield null;
                    }
                    case "clear" -> {
                        clears.incrementAndGet();
                        yield null;
                    }
                    case "isInTransaction" -> inTransaction;
                    case "withConsoleTransactionLock" -> {
                        locks.incrementAndGet();
                        yield ((Callable<?>) args[1]).call();
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ProceedingJoinPoint joinPoint(Object[] args, Callable<Object> proceed) {
        return (ProceedingJoinPoint) Proxy.newProxyInstance(
                ConnectionInfoHandlerLoggingTest.class.getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, method, methodArgs) -> switch (method.getName()) {
                    case "getArgs" -> args;
                    case "proceed" -> proceed.call();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
