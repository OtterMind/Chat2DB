package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.jcef.context.JcefContext;
import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void unacknowledgedDispatchRunsTheNativeFallback() throws Exception {
        CountDownLatch fallback = new CountDownLatch(1);

        assertTrue(ApplicationExitCoordinator.request("CLOSE", "ack-timeout-test", () -> {
            fallback.countDown();
            return true;
        }, 10L));

        assertTrue(fallback.await(1, TimeUnit.SECONDS));
        assertFalse(ApplicationExitCoordinator.cancel("ack-timeout-test"));
    }

    @Test
    void acknowledgedDispatchWaitsForTheUserDecision() throws Exception {
        CountDownLatch fallback = new CountDownLatch(1);

        assertTrue(ApplicationExitCoordinator.request("CLOSE", "acknowledged-test", () -> {
            fallback.countDown();
            return true;
        }, 20L));
        assertTrue(ApplicationExitCoordinator.acknowledge("acknowledged-test"));

        assertFalse(fallback.await(100, TimeUnit.MILLISECONDS));
        assertTrue(ApplicationExitCoordinator.cancel("acknowledged-test"));
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
}
