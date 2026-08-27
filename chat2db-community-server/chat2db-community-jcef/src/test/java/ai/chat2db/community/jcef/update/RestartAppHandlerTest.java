package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.handler.biz.update.RestartAppHandler;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartAppHandlerTest {

    private CefBrowser originalBrowser;

    @BeforeEach
    void setUp() {
        originalBrowser = JcefContext.getInstance().getBrowser_();
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        setBrowser(originalBrowser);
        ApplicationExitCoordinator.cancel("restart-test");
        ApplicationExitCoordinator.markFrontendUnavailable();
        DesktopUpdaterRegistry.resetForTests();
        System.clearProperty("chat2db.runtime.mode");
        System.clearProperty("chat2db.mode");
        System.clearProperty("chat2db.network.status");
    }

    @Test
    void acceptedRestartPreparesOnceAndExitsAfterResponse() {
        useHeadlessCommunityRuntime();
        StubDesktopUpdater updater = new StubDesktopUpdater(true);
        DesktopUpdaterRegistry.register(updater);

        CallbackResult callbackResult = restart();

        assertEquals(1, updater.prepareCount.get());
        assertEquals(1, updater.exitCount.get());
        assertEquals(0, callbackResult.failureCount.get());
        assertTrue(callbackResult.successResponse.get().contains("\"accepted\":true"));
    }

    @Test
    void rejectedRestartDoesNotExit() {
        useHeadlessCommunityRuntime();
        StubDesktopUpdater updater = new StubDesktopUpdater(false);
        DesktopUpdaterRegistry.register(updater);

        CallbackResult callbackResult = restart();

        assertEquals(1, updater.prepareCount.get());
        assertEquals(0, updater.exitCount.get());
        assertEquals(0, callbackResult.failureCount.get());
        assertTrue(callbackResult.successResponse.get().contains("\"accepted\":false"));
    }

    @Test
    void desktopRestartWaitsForApplicationExitConfirmation() throws ReflectiveOperationException {
        useDesktopCommunityRuntime();
        setBrowser(browserProxy());
        StubDesktopUpdater updater = new StubDesktopUpdater(true);
        DesktopUpdaterRegistry.register(updater);

        CallbackResult callbackResult = restart();

        assertEquals(0, updater.prepareCount.get());
        assertEquals(0, updater.exitCount.get());
        assertTrue(callbackResult.successResponse.get().contains("\"accepted\":true"));
        assertTrue(ApplicationExitCoordinator.confirm("restart-test"));
        assertEquals(1, updater.prepareCount.get());
        assertEquals(1, updater.exitCount.get());
    }

    @Test
    void cancellingDesktopExitLeavesRestartUnprepared() throws ReflectiveOperationException {
        useDesktopCommunityRuntime();
        setBrowser(browserProxy());
        StubDesktopUpdater updater = new StubDesktopUpdater(true);
        DesktopUpdaterRegistry.register(updater);

        restart();

        assertTrue(ApplicationExitCoordinator.cancel("restart-test"));
        assertEquals(0, updater.prepareCount.get());
        assertEquals(0, updater.exitCount.get());
    }

    @Test
    void desktopLocalRestartUsesTheSameApplicationExitProtocol() throws ReflectiveOperationException {
        useDesktopLocalRuntime();
        setBrowser(browserProxy());
        StubDesktopUpdater updater = new StubDesktopUpdater(true);
        DesktopUpdaterRegistry.register(updater);

        CallbackResult callbackResult = restart();

        assertEquals(0, updater.prepareCount.get());
        assertEquals(0, updater.exitCount.get());
        assertTrue(callbackResult.successResponse.get().contains("\"accepted\":true"));
        assertTrue(ApplicationExitCoordinator.confirm("restart-test"));
        assertEquals(1, updater.prepareCount.get());
        assertEquals(1, updater.exitCount.get());
    }

    @Test
    void desktopDispatchFailureFallsBackToTheConfirmedAction() throws ReflectiveOperationException {
        useDesktopCommunityRuntime();
        setBrowser(throwingBrowserProxy());
        StubDesktopUpdater updater = new StubDesktopUpdater(true);
        DesktopUpdaterRegistry.register(updater);

        CallbackResult callbackResult = restart();

        assertEquals(1, updater.prepareCount.get());
        assertEquals(1, updater.exitCount.get());
        assertTrue(callbackResult.successResponse.get().contains("\"accepted\":true"));
        assertFalse(ApplicationExitCoordinator.cancel("restart-test"));
    }

    @Test
    void mismatchedOperationCannotCancelDesktopRestart() throws ReflectiveOperationException {
        useDesktopCommunityRuntime();
        setBrowser(browserProxy());
        StubDesktopUpdater updater = new StubDesktopUpdater(true);
        DesktopUpdaterRegistry.register(updater);

        restart();
        CallbackResult overlappingRequest = restart();

        assertTrue(overlappingRequest.successResponse.get().contains("\"accepted\":false"));
        assertFalse(ApplicationExitCoordinator.cancel("different-operation"));
        assertTrue(ApplicationExitCoordinator.confirm("restart-test"));
        assertEquals(1, updater.prepareCount.get());
        assertEquals(1, updater.exitCount.get());
    }

    private CallbackResult restart() {
        ConsoleMessage message = new ConsoleMessage();
        message.setMessage("{\"operationId\":\"restart-test\"}");
        CallbackResult result = new CallbackResult();

        new RestartAppHandler().handle(message, new ConsoleResult(), result.callback());

        return result;
    }

    private void useHeadlessCommunityRuntime() {
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "WEB");
    }

    private void useDesktopCommunityRuntime() {
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "DESKTOP");
        ApplicationExitCoordinator.markFrontendReady();
    }

    private void useDesktopLocalRuntime() {
        System.setProperty("chat2db.runtime.mode", "pro");
        System.setProperty("chat2db.network.status", "OFFLINE");
        System.setProperty("chat2db.mode", "DESKTOP");
        ApplicationExitCoordinator.markFrontendReady();
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

    private CefBrowser throwingBrowserProxy() {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> {
                    if ("executeJavaScript".equals(method.getName())) {
                        throw new IllegalStateException("renderer unavailable");
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

    private static final class CallbackResult {
        private final AtomicReference<String> successResponse = new AtomicReference<>();
        private final AtomicInteger failureCount = new AtomicInteger();

        private CefQueryCallback callback() {
            return new CefQueryCallback() {
                @Override
                public void success(String response) {
                    successResponse.set(response);
                }

                @Override
                public void failure(int errorCode, String errorMessage) {
                    failureCount.incrementAndGet();
                }
            };
        }
    }

    private static final class StubDesktopUpdater implements IDesktopUpdater {
        private final boolean restartAccepted;
        private final AtomicInteger prepareCount = new AtomicInteger();
        private final AtomicInteger exitCount = new AtomicInteger();

        private StubDesktopUpdater(boolean restartAccepted) {
            this.restartAccepted = restartAccepted;
        }

        @Override
        public DesktopUpdateCheckResult appCheckUpdate() {
            return DesktopUpdateCheckResult.notAvailable();
        }

        @Override
        public boolean triggerDownload(ConsoleResult consoleResult) {
            return false;
        }

        @Override
        public boolean triggerInstallation() {
            return false;
        }

        @Override
        public boolean prepareRestart() {
            prepareCount.incrementAndGet();
            return restartAccepted;
        }

        @Override
        public void exitCurrentProcessAfterResponse() {
            exitCount.incrementAndGet();
        }
    }
}
