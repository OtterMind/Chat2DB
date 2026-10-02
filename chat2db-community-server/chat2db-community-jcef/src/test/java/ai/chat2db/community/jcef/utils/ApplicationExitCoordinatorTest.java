package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.jcef.context.JcefContext;
import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationExitCoordinatorTest {

    private CefBrowser originalBrowser;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        originalBrowser = JcefContext.getInstance().getBrowser_();
        setBrowser(browserProxy());
        ApplicationExitCoordinator.markFrontendReady();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        ApplicationExitCoordinator.cancel("ack-timeout-test");
        ApplicationExitCoordinator.cancel("acknowledged-test");
        ApplicationExitCoordinator.markFrontendUnavailable();
        setBrowser(originalBrowser);
    }

    @Test
    void unacknowledgedDispatchRunsTheNativeFallback() {
        AtomicInteger fallbackCount = new AtomicInteger();
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();

        assertTrue(ApplicationExitCoordinator.request("CLOSE", "ack-timeout-test", () -> {
            fallbackCount.incrementAndGet();
            return true;
        }, 10L, scheduler));

        assertEquals(0, fallbackCount.get());
        assertEquals(10L, scheduler.delayMillis);
        scheduler.runScheduledTask();
        assertEquals(1, fallbackCount.get());
        assertTrue(scheduler.timeoutFuture.isCancelled());
        assertFalse(ApplicationExitCoordinator.cancel("ack-timeout-test"));
    }

    @Test
    void acknowledgedDispatchWaitsForTheUserDecision() {
        AtomicInteger fallbackCount = new AtomicInteger();
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();

        assertTrue(ApplicationExitCoordinator.request("CLOSE", "acknowledged-test", () -> {
            fallbackCount.incrementAndGet();
            return true;
        }, 20L, scheduler));
        assertTrue(ApplicationExitCoordinator.acknowledge("acknowledged-test"));
        assertTrue(scheduler.timeoutFuture.isCancelled());

        scheduler.runScheduledTask();
        assertEquals(0, fallbackCount.get());
        assertTrue(ApplicationExitCoordinator.cancel("acknowledged-test"));
    }

    @Test
    void rendererUnavailableFallsBackAndReleasesAcknowledgedRequest() {
        AtomicInteger fallbackCount = new AtomicInteger();
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();

        assertTrue(ApplicationExitCoordinator.request("CLOSE", "acknowledged-test", () -> {
            fallbackCount.incrementAndGet();
            return true;
        }, 20L, scheduler));
        assertTrue(ApplicationExitCoordinator.acknowledge("acknowledged-test"));

        ApplicationExitCoordinator.markFrontendUnavailable();
        assertTrue(scheduler.timeoutFuture.isCancelled());
        scheduler.runScheduledTask();

        assertEquals(1, fallbackCount.get());
        assertFalse(ApplicationExitCoordinator.cancel("acknowledged-test"));
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "next-test", () -> {
            fallbackCount.incrementAndGet();
            return true;
        }));
        assertEquals(2, fallbackCount.get());
    }

    @Test
    void acknowledgementAndTimeoutHaveOneAtomicWinner() throws Exception {
        AtomicInteger fallbackCount = new AtomicInteger();
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "race-test", () -> {
            fallbackCount.incrementAndGet();
            return true;
        }, 20L, scheduler));

        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> acknowledgement = executor.submit(() -> {
                start.await();
                return ApplicationExitCoordinator.acknowledge("race-test");
            });
            Future<?> timeout = executor.submit(() -> {
                start.await();
                scheduler.runScheduledTask();
                return null;
            });

            start.await();
            boolean acknowledged = acknowledgement.get(1, TimeUnit.SECONDS);
            timeout.get(1, TimeUnit.SECONDS);

            assertEquals(acknowledged ? 0 : 1, fallbackCount.get());
            assertEquals(acknowledged, ApplicationExitCoordinator.cancel("race-test"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalDecisionsCancelTheAcknowledgementTimeout() {
        AtomicInteger confirmedCount = new AtomicInteger();
        ManualTimeoutScheduler confirmScheduler = new ManualTimeoutScheduler();
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "confirm-test", () -> {
            confirmedCount.incrementAndGet();
            return true;
        }, 20L, confirmScheduler));

        assertTrue(ApplicationExitCoordinator.confirm("confirm-test"));
        assertTrue(confirmScheduler.timeoutFuture.isCancelled());
        confirmScheduler.runScheduledTask();
        assertEquals(1, confirmedCount.get());

        ApplicationExitCoordinator.markFrontendReady();
        ManualTimeoutScheduler cancelScheduler = new ManualTimeoutScheduler();
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "cancel-test", () -> {
            confirmedCount.incrementAndGet();
            return true;
        }, 20L, cancelScheduler));

        assertTrue(ApplicationExitCoordinator.cancel("cancel-test"));
        assertTrue(cancelScheduler.timeoutFuture.isCancelled());
        cancelScheduler.runScheduledTask();
        assertEquals(1, confirmedCount.get());
    }

    @Test
    void schedulerRejectionClaimsTheDispatchedRequest() {
        AtomicInteger fallbackCount = new AtomicInteger();

        assertTrue(ApplicationExitCoordinator.request("CLOSE", "rejection-test", () -> {
            fallbackCount.incrementAndGet();
            return true;
        }, 20L, (task, delayMillis) -> {
            throw new RejectedExecutionException("scheduler stopped");
        }));

        assertEquals(1, fallbackCount.get());
        assertFalse(ApplicationExitCoordinator.cancel("rejection-test"));
    }

    @Test
    void timeoutCancellationFailureDoesNotBlockExitDecisions() {
        AtomicInteger confirmedCount = new AtomicInteger();
        ManualTimeoutScheduler acknowledgementScheduler = new ManualTimeoutScheduler(true);
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "acknowledged-test", () -> {
            confirmedCount.incrementAndGet();
            return true;
        }, 20L, acknowledgementScheduler));

        assertTrue(ApplicationExitCoordinator.acknowledge("acknowledged-test"));
        assertTrue(ApplicationExitCoordinator.confirm("acknowledged-test"));
        assertEquals(1, confirmedCount.get());

        ApplicationExitCoordinator.markFrontendReady();
        ManualTimeoutScheduler confirmationScheduler = new ManualTimeoutScheduler(true);
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "confirm-test", () -> {
            confirmedCount.incrementAndGet();
            return true;
        }, 20L, confirmationScheduler));

        assertTrue(ApplicationExitCoordinator.confirm("confirm-test"));
        assertEquals(2, confirmedCount.get());
    }

    @Test
    void browserUnavailableRejectsAConcurrentDirectFallback() throws Exception {
        setBrowser(null);
        ApplicationExitCoordinator.markFrontendReady();
        AtomicInteger fallbackCount = new AtomicInteger();
        CountDownLatch fallbackStarted = new CountDownLatch(1);
        CountDownLatch finishFallback = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstRequest = executor.submit(() -> ApplicationExitCoordinator.request(
                    "CLOSE", "direct-first", () -> {
                        fallbackCount.incrementAndGet();
                        fallbackStarted.countDown();
                        try {
                            return finishFallback.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }));

            assertTrue(fallbackStarted.await(1, TimeUnit.SECONDS));
            Future<Boolean> overlappingRequest = executor.submit(() -> ApplicationExitCoordinator.request(
                    "CLOSE", "direct-second", () -> {
                        fallbackCount.incrementAndGet();
                        return true;
                    }));

            assertFalse(overlappingRequest.get(1, TimeUnit.SECONDS));
            assertEquals(1, fallbackCount.get());
            finishFallback.countDown();
            assertTrue(firstRequest.get(1, TimeUnit.SECONDS));
        } finally {
            finishFallback.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void rendererUnavailableClaimsARequestPublishedBeforeDispatchCompletes() throws Exception {
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        CountDownLatch finishDispatch = new CountDownLatch(1);
        setBrowser(blockingBrowserProxy(dispatchStarted, finishDispatch));
        AtomicInteger fallbackCount = new AtomicInteger();
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> request = executor.submit(() -> ApplicationExitCoordinator.request(
                    "CLOSE", "publish-race-test", () -> {
                        fallbackCount.incrementAndGet();
                        return true;
                    }, 20L, scheduler));

            assertTrue(dispatchStarted.await(1, TimeUnit.SECONDS));
            ApplicationExitCoordinator.markFrontendUnavailable();
            finishDispatch.countDown();

            assertTrue(request.get(1, TimeUnit.SECONDS));
            assertEquals(1, fallbackCount.get());
            assertTrue(scheduler.timeoutFuture.isCancelled());
            assertFalse(ApplicationExitCoordinator.cancel("publish-race-test"));
        } finally {
            finishDispatch.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void exposesFrontendReadiness() {
        ApplicationExitCoordinator.markFrontendUnavailable();
        assertFalse(ApplicationExitCoordinator.isFrontendReady());

        ApplicationExitCoordinator.markFrontendReady();
        assertTrue(ApplicationExitCoordinator.isFrontendReady());
    }

    private void setBrowser(CefBrowser browser) throws ReflectiveOperationException {
        java.lang.reflect.Field field = JcefContext.class.getDeclaredField("browser_");
        field.setAccessible(true);
        field.set(JcefContext.getInstance(), browser);
    }

    private CefBrowser browserProxy() {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> {
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    if (method.getReturnType().equals(long.class)) {
                        return 0L;
                    }
                    return null;
                }
        );
    }

    private CefBrowser blockingBrowserProxy(CountDownLatch dispatchStarted, CountDownLatch finishDispatch) {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> {
                    if ("executeJavaScript".equals(method.getName())) {
                        dispatchStarted.countDown();
                        if (!finishDispatch.await(1, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to finish dispatch");
                        }
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    if (method.getReturnType().equals(long.class)) {
                        return 0L;
                    }
                    return null;
                }
        );
    }

    private static final class ManualTimeoutScheduler
            implements ApplicationExitCoordinator.AcknowledgementTimeoutScheduler {
        private final AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        private final ManualScheduledFuture timeoutFuture;
        private long delayMillis;

        private ManualTimeoutScheduler() {
            this(false);
        }

        private ManualTimeoutScheduler(boolean throwOnCancel) {
            timeoutFuture = new ManualScheduledFuture(throwOnCancel);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
            assertTrue(scheduledTask.compareAndSet(null, task));
            this.delayMillis = delayMillis;
            return timeoutFuture;
        }

        private void runScheduledTask() {
            scheduledTask.get().run();
        }
    }

    private static final class ManualScheduledFuture implements ScheduledFuture<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final boolean throwOnCancel;

        private ManualScheduledFuture(boolean throwOnCancel) {
            this.throwOnCancel = throwOnCancel;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (throwOnCancel) {
                throw new IllegalStateException("timeout cancellation failed");
            }
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
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
}
