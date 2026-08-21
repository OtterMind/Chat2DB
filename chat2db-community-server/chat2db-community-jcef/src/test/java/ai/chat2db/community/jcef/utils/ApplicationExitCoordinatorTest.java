package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.jcef.context.JcefContext;
import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationExitCoordinatorTest {

    private CefBrowser originalBrowser;

    @BeforeEach
    void setUp() {
        originalBrowser = JcefContext.getInstance().getBrowser_();
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "DESKTOP");
        ApplicationExitCoordinator.resetForTests();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        setBrowser(originalBrowser);
        ApplicationExitCoordinator.resetForTests();
        System.clearProperty("chat2db.runtime.mode");
        System.clearProperty("chat2db.mode");
    }

    @Test
    void unexpiredRequestBlocksOverlap() throws ReflectiveOperationException {
        AtomicLong clock = new AtomicLong(10L);
        ApplicationExitCoordinator.setNanoClockForTests(clock::get);
        setBrowser(browserProxy(new ArrayList<>()));

        assertTrue(ApplicationExitCoordinator.request("RESTART", "first", () -> true));
        clock.addAndGet(ApplicationExitCoordinator.PENDING_EXIT_TIMEOUT_NANOS - 1L);
        assertFalse(ApplicationExitCoordinator.request("CLOSE", "second", () -> true));
        assertTrue(ApplicationExitCoordinator.cancel("first"));
    }

    @Test
    void expiredRequestFailsAndAllowsRetryWhileStaleOperationCannotComplete() throws ReflectiveOperationException {
        AtomicLong clock = new AtomicLong(10L);
        List<String> scripts = new ArrayList<>();
        ApplicationExitCoordinator.setNanoClockForTests(clock::get);
        setBrowser(browserProxy(scripts));

        assertTrue(ApplicationExitCoordinator.request("RESTART", "expired", () -> true));
        clock.addAndGet(ApplicationExitCoordinator.PENDING_EXIT_TIMEOUT_NANOS);
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "replacement", () -> true));

        assertTrue(scripts.stream().anyMatch(script -> script.contains("expired") && script.contains("FAILED")));
        assertFalse(ApplicationExitCoordinator.confirm("expired"));
        assertFalse(ApplicationExitCoordinator.cancel("expired"));
        assertTrue(ApplicationExitCoordinator.cancel("replacement"));
    }

    @Test
    void schedulerActivelyExpiresSilentRequestAndPublishesFailure() throws ReflectiveOperationException {
        List<String> scripts = new ArrayList<>();
        AtomicReference<Runnable> timeoutTask = new AtomicReference<>();
        AtomicBoolean timeoutCancelled = new AtomicBoolean();
        AtomicLong scheduledDelayNanos = new AtomicLong();
        ApplicationExitCoordinator.setTimeoutSchedulerForTests((task, delay, unit) -> {
            timeoutTask.set(task);
            scheduledDelayNanos.set(unit.toNanos(delay));
            return () -> timeoutCancelled.set(true);
        });
        setBrowser(browserProxy(scripts));

        assertTrue(ApplicationExitCoordinator.request("INSTALL_UPDATE", "silent", () -> true));
        assertEquals(ApplicationExitCoordinator.PENDING_EXIT_TIMEOUT_NANOS, scheduledDelayNanos.get());

        timeoutTask.get().run();

        assertTrue(timeoutCancelled.get());
        assertTrue(scripts.stream().anyMatch(script -> script.contains("silent") && script.contains("FAILED")));
        assertFalse(ApplicationExitCoordinator.confirm("silent"));
        assertTrue(ApplicationExitCoordinator.request("CLOSE", "replacement", () -> true));
        assertTrue(ApplicationExitCoordinator.cancel("replacement"));
    }

    @Test
    void confirmedRequestIgnoresPreviouslyCapturedTimeoutTask() throws ReflectiveOperationException {
        List<String> scripts = new ArrayList<>();
        AtomicReference<Runnable> timeoutTask = new AtomicReference<>();
        AtomicBoolean timeoutCancelled = new AtomicBoolean();
        ApplicationExitCoordinator.setTimeoutSchedulerForTests((task, delay, unit) -> {
            timeoutTask.set(task);
            return () -> timeoutCancelled.set(true);
        });
        setBrowser(browserProxy(scripts));

        assertTrue(ApplicationExitCoordinator.request("INSTALL_UPDATE", "confirmed", () -> true));
        assertTrue(ApplicationExitCoordinator.confirm("confirmed"));
        assertTrue(timeoutCancelled.get());
        assertEquals(1L, resultCount(scripts, "confirmed", "ACCEPTED"));
        assertEquals(0L, resultCount(scripts, "confirmed", "FAILED"));

        timeoutTask.get().run();

        assertEquals(1L, resultCount(scripts, "confirmed", "ACCEPTED"));
        assertEquals(0L, resultCount(scripts, "confirmed", "FAILED"));
    }

    @Test
    void cancelledRequestIgnoresPreviouslyCapturedTimeoutTask() throws ReflectiveOperationException {
        List<String> scripts = new ArrayList<>();
        AtomicReference<Runnable> timeoutTask = new AtomicReference<>();
        AtomicBoolean timeoutCancelled = new AtomicBoolean();
        ApplicationExitCoordinator.setTimeoutSchedulerForTests((task, delay, unit) -> {
            timeoutTask.set(task);
            return () -> timeoutCancelled.set(true);
        });
        setBrowser(browserProxy(scripts));

        assertTrue(ApplicationExitCoordinator.request("INSTALL_UPDATE", "cancelled", () -> true));
        assertTrue(ApplicationExitCoordinator.cancel("cancelled"));
        assertTrue(timeoutCancelled.get());
        assertEquals(1L, resultCount(scripts, "cancelled", "CANCELLED"));
        assertEquals(0L, resultCount(scripts, "cancelled", "FAILED"));

        timeoutTask.get().run();

        assertEquals(1L, resultCount(scripts, "cancelled", "CANCELLED"));
        assertEquals(0L, resultCount(scripts, "cancelled", "FAILED"));
    }

    private long resultCount(List<String> scripts, String operationId, String result) {
        return scripts.stream()
                .filter(script -> script.contains(operationId) && script.contains(result))
                .count();
    }

    private void setBrowser(CefBrowser browser) throws ReflectiveOperationException {
        Field field = JcefContext.class.getDeclaredField("browser_");
        field.setAccessible(true);
        field.set(JcefContext.getInstance(), browser);
    }

    private CefBrowser browserProxy(List<String> scripts) {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("executeJavaScript")) {
                        scripts.add((String) args[0]);
                        return null;
                    }
                    if (method.getName().equals("getURL")) {
                        return "about:blank";
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
}
