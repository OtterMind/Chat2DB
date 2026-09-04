package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.domain.api.service.db.IDbSqlExecutionService;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExecutionJobTransactionLockTest {

    @Test
    void consoleExecutionUsesTheLockEvenWhenNoTransactionIsOpen() {
        AtomicInteger lockCount = new AtomicInteger();
        AtomicInteger callbackCount = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();

        SqlExecutionJob job = job(connectionContextService(false, lockCount, new AtomicInteger(), null, null),
                events, callbackCount);

        job.run();

        assertEquals(1, lockCount.get());
        assertEquals(List.of("started", "finished"), events);
        assertEquals(1, callbackCount.get());
    }

    @Test
    void cancellationWhileWaitingForConsoleLockDoesNotEnterExecution() throws Exception {
        AtomicInteger lockCount = new AtomicInteger();
        AtomicInteger bindCount = new AtomicInteger();
        AtomicInteger callbackCount = new AtomicInteger();
        CountDownLatch lockEntered = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        SqlExecutionJob job = job(
                connectionContextService(false, lockCount, bindCount, lockEntered, releaseLock),
                events,
                callbackCount
        );

        Thread runner = new Thread(job, "sql-execution-job-test");
        runner.start();
        assertTrue(lockEntered.await(2, TimeUnit.SECONDS));

        job.cancel();
        releaseLock.countDown();
        runner.join(2_000L);

        assertFalse(runner.isAlive());
        assertEquals(1, lockCount.get());
        assertEquals(0, bindCount.get());
        assertEquals(List.of("cancelled"), events);
        assertEquals(1, callbackCount.get());
    }

    private static SqlExecutionJob job(
            IDbConnectionContextService connectionContextService,
            List<String> events,
            AtomicInteger callbackCount
    ) {
        SqlEditorExecuteRequest editorRequest = new SqlEditorExecuteRequest();
        editorRequest.setDataSourceId(42L);
        editorRequest.setConsoleId(84L);
        editorRequest.setSql("SELECT 1");

        DbConnectionContextRequest connectionContext = new DbConnectionContextRequest();
        connectionContext.setDataSourceId(42L);
        connectionContext.setConsoleId(84L);

        SqlExecutionRequest request = SqlExecutionRequest.builder()
                .executionId("execution-1")
                .laneId("console:84")
                .sqlEditorRequest(editorRequest)
                .connectionContext(connectionContext)
                .consoleMessage(new ai.chat2db.community.tools.console.ConsoleMessage())
                .build();

        ISqlExecutionSink sink = (eventType, message, statementSequence, resultSequence) -> events.add(eventType);
        IDbSqlExecutionService sqlExecutionService = requestParam -> {
            // The test only exercises the job's lock and cancellation boundary.
        };
        IOpsSqlOperationLogService recorder = noOp(IOpsSqlOperationLogService.class);
        DbWebConverter converter = Mappers.getMapper(DbWebConverter.class);
        return new SqlExecutionJob(
                request,
                sink,
                connectionContextService,
                sqlExecutionService,
                converter,
                (IDbLargeValueTokenService) null,
                (IDbExecuteResultEnhanceService) null,
                recorder,
                ignored -> callbackCount.incrementAndGet()
        );
    }

    private static IDbConnectionContextService connectionContextService(
            boolean inTransaction,
            AtomicInteger lockCount,
            AtomicInteger bindCount,
            CountDownLatch lockEntered,
            CountDownLatch releaseLock
    ) {
        return (IDbConnectionContextService) Proxy.newProxyInstance(
                SqlExecutionJobTransactionLockTest.class.getClassLoader(),
                new Class<?>[]{IDbConnectionContextService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isInTransaction" -> inTransaction;
                    case "bind" -> {
                        bindCount.incrementAndGet();
                        yield null;
                    }
                    case "withConsoleTransactionLock" -> {
                        lockCount.incrementAndGet();
                        if (lockEntered != null) {
                            lockEntered.countDown();
                            releaseLock.await(2, TimeUnit.SECONDS);
                        }
                        yield ((Callable<?>) args[1]).call();
                    }
                    case "clear" -> null;
                    case "currentProfile" -> (ConnectionProfile) null;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T noOp(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                SqlExecutionJobTransactionLockTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );
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
