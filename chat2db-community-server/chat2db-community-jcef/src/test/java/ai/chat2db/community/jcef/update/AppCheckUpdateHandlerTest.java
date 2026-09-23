package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.handler.biz.update.AppCheckUpdateHandler;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.cef.callback.CefQueryCallback;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler behaviour for an update check.
 *
 * <p>The reporting itself is covered by the updater module tests, which inject a store and a transport,
 * and by the on-device verification; the checks here run with {@code offlineActivation} set, so the
 * handler never touches the network.</p>
 */
class AppCheckUpdateHandlerTest {

    @BeforeEach
    void setUp() {
        System.setProperty("chat2db.runtime.mode", "community");
    }

    @AfterEach
    void tearDown() {
        DesktopUpdaterRegistry.resetForTests();
        System.clearProperty("chat2db.runtime.mode");
    }

    @Test
    void reportsAnAvailableUpdate() throws Exception {
        DesktopUpdaterRegistry.register(new StubUpdater(new DesktopUpdateCheckResult(true, "5.3.8")));

        CallbackResult callback = check("{\"trigger\":\"startup\",\"offlineActivation\":true}");

        assertEquals(0, callback.failureCount.get());
        assertTrue(callback.successResponse.get().contains("\"status\":\"available\""), callback.successResponse.get());
        assertTrue(callback.successResponse.get().contains("\"version\":\"5.3.8\""));
    }

    @Test
    void reportsNoUpdateWithoutAVersion() throws Exception {
        DesktopUpdaterRegistry.register(new StubUpdater(DesktopUpdateCheckResult.notAvailable()));

        CallbackResult callback = check("{\"trigger\":\"manual\",\"offlineActivation\":true}");

        assertEquals(0, callback.failureCount.get());
        assertTrue(callback.successResponse.get().contains("\"status\":\"notAvailable\""), callback.successResponse.get());
        assertTrue(callback.successResponse.get().contains("\"version\":\"\""));
    }

    @Test
    void reportsACheckFailureInsteadOfNoUpdate() throws Exception {
        DesktopUpdaterRegistry.register(new StubUpdater(DesktopUpdateCheckResult.checkFailed()));

        CallbackResult callback = check("{\"trigger\":\"manual\",\"offlineActivation\":true}");

        assertEquals(0, callback.failureCount.get());
        assertTrue(callback.successResponse.get().contains("\"status\":\"updateFailed\""),
            callback.successResponse.get());
    }

    private CallbackResult check(String message) throws Exception {
        ConsoleMessage consoleMessage = new ConsoleMessage();
        consoleMessage.setMessage(message);
        CallbackResult result = new CallbackResult();
        new AppCheckUpdateHandler().handle(consoleMessage, new ConsoleResult(), result.callback());
        return result;
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

    private static final class StubUpdater implements IDesktopUpdater {
        private final DesktopUpdateCheckResult result;

        private StubUpdater(DesktopUpdateCheckResult result) {
            this.result = result;
        }

        @Override
        public DesktopUpdateCheckResult appCheckUpdate() {
            return result;
        }

        @Override
        public String installedVersion() {
            return "5.3.7-dev";
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
            return false;
        }

        @Override
        public void exitCurrentProcessAfterResponse() {
        }
    }
}
