package ai.chat2db.community.jcef.utils;

import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableCefQueryCallbackTest {

    @Test
    void cancellationSuppressesLateCompletion() {
        RecordingCallback delegate = new RecordingCallback();
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);

        callback.cancel();
        callback.success("late");
        callback.failure(500, "late");

        assertEquals(0, delegate.successCount);
        assertEquals(0, delegate.failureCount);
    }

    @Test
    void onlyTheFirstCompletionReachesTheNativeCallback() {
        RecordingCallback delegate = new RecordingCallback();
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);

        callback.success("first");
        callback.success("second");
        callback.failure(500, "late");

        assertEquals(1, delegate.successCount);
        assertEquals("first", delegate.response);
        assertEquals(0, delegate.failureCount);
    }

    @Test
    void cancellationInterruptsTheAttachedWorker() throws Exception {
        RecordingCallback delegate = new RecordingCallback();
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            Future<?> worker = executor.submit(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            callback.attachWorker(worker);
            assertTrue(started.await(1, TimeUnit.SECONDS));

            callback.cancel();

            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertTrue(worker.isCancelled());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void workerAttachedAfterCancellationIsCancelledImmediately() {
        RecordingCallback delegate = new RecordingCallback();
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);
        RecordingFuture worker = new RecordingFuture();

        callback.cancel();
        callback.attachWorker(worker);

        assertTrue(worker.cancelled);
        assertTrue(worker.mayInterruptIfRunning);
    }

    @Test
    void boundFutureDoesNotRunWhenCancelledBeforeExecutorStartsIt() {
        RecordingCallback delegate = new RecordingCallback();
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);
        ControlledExecutor executor = new ControlledExecutor();
        AtomicInteger runCount = new AtomicInteger();
        FutureTask<Void> worker = new FutureTask<>(() -> {
            runCount.incrementAndGet();
            return null;
        });

        callback.attachWorker(worker);
        executor.execute(worker);
        callback.cancel();
        executor.runPending();

        assertTrue(worker.isCancelled());
        assertEquals(0, runCount.get());
    }

    @Test
    void cancellationCannotInterleaveWithNativeCallbackInvocation() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch finishCallback = new CountDownLatch(1);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch cancellationReturned = new CountDownLatch(1);
        BlockingCallback delegate = new BlockingCallback(callbackEntered, finishCallback);
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> completion = executor.submit(() -> callback.success("response"));
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            Future<?> cancellation = executor.submit(() -> {
                cancellationStarted.countDown();
                callback.cancel();
                cancellationReturned.countDown();
            });

            assertTrue(cancellationStarted.await(1, TimeUnit.SECONDS));
            assertFalse(cancellationReturned.await(100, TimeUnit.MILLISECONDS));
            finishCallback.countDown();

            completion.get(1, TimeUnit.SECONDS);
            cancellation.get(1, TimeUnit.SECONDS);
            callback.failure(500, "late");
            assertEquals(1, delegate.successCount);
            assertEquals(0, delegate.failureCount);
        } finally {
            finishCallback.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static class RecordingCallback implements CefQueryCallback {
        protected int successCount;
        protected int failureCount;
        private String response;

        @Override
        public void success(String value) {
            successCount++;
            response = value;
        }

        @Override
        public void failure(int errorCode, String errorMessage) {
            failureCount++;
        }
    }

    private static final class BlockingCallback extends RecordingCallback {
        private final CountDownLatch entered;
        private final CountDownLatch finish;

        private BlockingCallback(CountDownLatch entered, CountDownLatch finish) {
            this.entered = entered;
            this.finish = finish;
        }

        @Override
        public void success(String value) {
            entered.countDown();
            try {
                if (!finish.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to finish callback");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Callback interrupted", exception);
            }
            super.success(value);
        }
    }

    private static final class RecordingFuture implements Future<Object> {
        private boolean cancelled;
        private boolean mayInterruptIfRunning;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            this.mayInterruptIfRunning = mayInterruptIfRunning;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ControlledExecutor implements Executor {
        private final AtomicReference<Runnable> pending = new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            assertTrue(pending.compareAndSet(null, command));
        }

        private void runPending() {
            pending.get().run();
        }
    }
}
