package ai.chat2db.community.domain.api.model.async;

import ai.chat2db.community.domain.api.service.task.ITaskAsyncCall;
import ai.chat2db.community.tools.model.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for code-review finding core:domain-api-2:
 * the finish flag (and the progress/info/error state read by the callback
 * thread) must be safely published so the polling thread terminates.
 */
class AsyncContextTest {

    @TempDir
    Path tempDir;

    @Test
    void sharedStateFieldsAreVolatile() throws Exception {
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("finish").getModifiers()),
                "finish must be volatile so the polling thread observes stop()/finish()");
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("progress").getModifiers()),
                "progress is written by the task thread and read by the callback thread");
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("info").getModifiers()),
                "info is reassigned in callUpdate() and appended by the task thread");
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("error").getModifiers()),
                "error is reassigned in callUpdate() and appended by the task thread");
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("state").getModifiers()),
                "state is written by cancellation and read by the task and callback threads");
    }

    @Test
    void stopRemainsTerminalWhenTaskFinallyFinishes() {
        List<Map<String, Object>> updates = new ArrayList<>();
        ITaskAsyncCall call = update -> updates.add(new HashMap<>(update));
        AsyncContext context = new AsyncContext(
                call, null, tempDir.resolve("cancelled.sql").toFile(), true);

        context.stop();
        context.finish();
        context.finish();

        assertTrue(context.isStopped());
        assertEquals(1, updates.size(), "STOP must be published at most once");
        assertEquals("STOP", updates.get(0).get("status"));
        assertEquals("", updates.get(0).get("downloadUrl"),
                "cancelled exports must explicitly clear a stale download URL");
    }

    @Test
    void stopDoesNotInvokeThrowingCallback() {
        AtomicInteger updateCount = new AtomicInteger();
        AsyncContext context = new AsyncContext(update -> {
            updateCount.incrementAndGet();
            throw new IllegalStateException("storage unavailable");
        }, null, null, false);

        assertTrue(assertDoesNotThrow(context::stop));

        assertTrue(context.isStopped());
        assertEquals(0, updateCount.get(), "stop must not wait for or invoke external storage");
    }

    @Test
    void blockedPollingUpdateIsFollowedByOneTerminalUpdateWithoutEmptyLogs() throws Exception {
        CountDownLatch runningUpdateStarted = new CountDownLatch(1);
        CountDownLatch releaseRunningUpdate = new CountDownLatch(1);
        AtomicBoolean firstUpdate = new AtomicBoolean(true);
        List<Map<String, Object>> updates = new CopyOnWriteArrayList<>();
        ITaskAsyncCall call = update -> {
            updates.add(new HashMap<>(update));
            if (firstUpdate.compareAndSet(true, false)) {
                runningUpdateStarted.countDown();
                try {
                    if (!releaseRunningUpdate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release callback");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        AsyncContext context = new AsyncContext(call, new Context(), null, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            assertTrue(runningUpdateStarted.await(5, TimeUnit.SECONDS), "polling callback did not start");
            executor.submit(context::stop).get(1, TimeUnit.SECONDS);

            var finishFuture = executor.submit(context::finish);
            releaseRunningUpdate.countDown();
            finishFuture.get(5, TimeUnit.SECONDS);
            context.finish();

            assertEquals(List.of("RUNNING", "STOP"),
                    updates.stream().map(update -> String.valueOf(update.get("status"))).toList());
            assertFalse(updates.get(1).containsKey("info"),
                    "an empty terminal info field would erase the previously persisted log");
            assertFalse(updates.get(1).containsKey("error"),
                    "an empty terminal error field would erase the previously persisted log");
        } finally {
            releaseRunningUpdate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulFinishPublishesDownload() {
        List<Map<String, Object>> updates = new ArrayList<>();
        ITaskAsyncCall call = update -> updates.add(new HashMap<>(update));
        Path output = tempDir.resolve("finished.sql");
        AsyncContext context = new AsyncContext(call, null, output.toFile(), true);

        context.finish();

        assertEquals(1, updates.size());
        assertEquals("FINISHED", updates.get(0).get("status"));
        assertEquals(100, updates.get(0).get("progress"));
        assertEquals(output.toFile().getAbsolutePath(), updates.get(0).get("downloadUrl"));
        assertFalse(context.stop(), "a published FINISHED state must reject cancellation");
        assertEquals(1, updates.size());
    }

    @Test
    void cancellationDuringFinishedCallbackPublishesCompensatingStop() throws Exception {
        CountDownLatch finishedUpdateStarted = new CountDownLatch(1);
        CountDownLatch releaseFinishedUpdate = new CountDownLatch(1);
        List<Map<String, Object>> updates = new CopyOnWriteArrayList<>();
        ITaskAsyncCall call = update -> {
            updates.add(new HashMap<>(update));
            if ("FINISHED".equals(update.get("status"))) {
                finishedUpdateStarted.countDown();
                try {
                    if (!releaseFinishedUpdate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release FINISHED callback");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        Path output = tempDir.resolve("stale-finished.sql");
        AsyncContext context = new AsyncContext(call, null, output.toFile(), true);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> finishFuture = executor.submit(context::finish);
            assertTrue(finishedUpdateStarted.await(5, TimeUnit.SECONDS), "FINISHED callback did not start");
            assertTrue(context.stop(), "in-flight FINISHED must still accept cancellation");
            releaseFinishedUpdate.countDown();
            finishFuture.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("FINISHED", "STOP"),
                    updates.stream().map(update -> String.valueOf(update.get("status"))).toList());
            assertEquals(output.toFile().getAbsolutePath(), updates.get(0).get("downloadUrl"));
            assertEquals("", updates.get(1).get("downloadUrl"));
        } finally {
            releaseFinishedUpdate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void terminalCallbackRetriesWithoutLosingOrDuplicatingMessages() {
        AtomicInteger attempts = new AtomicInteger();
        List<Map<String, Object>> attemptedUpdates = new ArrayList<>();
        AsyncContext context = new AsyncContext(update -> {
            attemptedUpdates.add(new HashMap<>(update));
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary storage failure");
            }
        }, null, null, false);
        context.info("before finish");

        context.finish();

        assertEquals(2, attempts.get());
        assertEquals("FINISHED", attemptedUpdates.get(1).get("status"));
        assertEquals(attemptedUpdates.get(0).get("info"), attemptedUpdates.get(1).get("info"));
        assertTrue(String.valueOf(attemptedUpdates.get(1).get("info")).contains("before finish"));
    }

    @Test
    void stoppedContextRetriesTerminalCallback() {
        AtomicInteger attempts = new AtomicInteger();
        List<Map<String, Object>> attemptedUpdates = new ArrayList<>();
        AsyncContext context = new AsyncContext(update -> {
            attemptedUpdates.add(new HashMap<>(update));
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary storage failure");
            }
        }, null, tempDir.resolve("cancelled-retry.sql").toFile(), true);

        assertTrue(context.stop());
        context.finish();

        assertEquals(2, attempts.get());
        assertEquals("STOP", attemptedUpdates.get(1).get("status"));
        assertEquals("", attemptedUpdates.get(1).get("downloadUrl"));
    }

    @Test
    void pollingCallbackContinuesAfterTransientFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch retryStarted = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        List<Map<String, Object>> attemptedUpdates = new CopyOnWriteArrayList<>();
        AsyncContext context = new AsyncContext(update -> {
            attemptedUpdates.add(new HashMap<>(update));
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new IllegalStateException("temporary polling failure");
            }
            if (attempt == 2) {
                retryStarted.countDown();
                try {
                    if (!releaseRetry.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release polling retry");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }, new Context(), null, false);

        try {
            assertTrue(retryStarted.await(5, TimeUnit.SECONDS), "polling callback was not retried");
            assertTrue(context.stop());
            releaseRetry.countDown();
            context.finish();

            assertEquals(3, attempts.get());
            assertEquals("STOP", attemptedUpdates.get(2).get("status"));
        } finally {
            releaseRetry.countDown();
            context.stop();
            context.finish();
        }
    }

    @Test
    void stopCancelsCurrentStatement() {
        AtomicInteger cancellations = new AtomicInteger();
        AsyncContext context = new AsyncContext(null, null, null, false);
        context.onStatementCreated(statement(cancellations, false));

        assertTrue(context.stop());

        assertEquals(1, cancellations.get());
    }

    @Test
    void statementCreatedAfterStopIsCancelledImmediately() {
        AtomicInteger cancellations = new AtomicInteger();
        AsyncContext context = new AsyncContext(null, null, null, false);
        assertTrue(context.stop());

        context.onStatementCreated(statement(cancellations, false));

        assertEquals(1, cancellations.get());
    }

    @Test
    void closingOldStatementDoesNotForgetNewStatement() {
        AtomicInteger oldCancellations = new AtomicInteger();
        AtomicInteger newCancellations = new AtomicInteger();
        Statement oldStatement = statement(oldCancellations, false);
        Statement newStatement = statement(newCancellations, false);
        AsyncContext context = new AsyncContext(null, null, null, false);
        context.onStatementCreated(oldStatement);
        context.onStatementCreated(newStatement);

        context.onStatementClosed(oldStatement);
        assertTrue(context.stop());

        assertEquals(0, oldCancellations.get());
        assertEquals(1, newCancellations.get());
    }

    @Test
    void statementCancellationFailureDoesNotChangeStopDecision() {
        AtomicInteger cancellations = new AtomicInteger();
        AsyncContext context = new AsyncContext(null, null, null, false);
        context.onStatementCreated(statement(cancellations, true));

        assertTrue(assertDoesNotThrow(context::stop));

        assertTrue(context.isStopped());
        assertEquals(1, cancellations.get());
    }

    private static Statement statement(AtomicInteger cancellations, boolean failCancellation) {
        return (Statement) Proxy.newProxyInstance(
                AsyncContextTest.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        cancellations.incrementAndGet();
                        if (failCancellation) {
                            throw new IllegalStateException("driver cancellation failed");
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
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
