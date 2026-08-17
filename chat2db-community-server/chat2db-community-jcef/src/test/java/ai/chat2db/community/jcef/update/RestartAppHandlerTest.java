package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.handler.biz.update.RestartAppHandler;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartAppHandlerTest {

    @AfterEach
    void tearDown() {
        DesktopUpdaterRegistry.resetForTests();
        System.clearProperty("chat2db.runtime.mode");
        System.clearProperty("chat2db.mode");
    }

    @Test
    void acceptedRestartPreparesOnceAndExitsAfterResponse() {
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
        StubDesktopUpdater updater = new StubDesktopUpdater(false);
        DesktopUpdaterRegistry.register(updater);

        CallbackResult callbackResult = restart();

        assertEquals(1, updater.prepareCount.get());
        assertEquals(0, updater.exitCount.get());
        assertEquals(0, callbackResult.failureCount.get());
        assertTrue(callbackResult.successResponse.get().contains("\"accepted\":false"));
    }

    private CallbackResult restart() {
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "WEB");
        ConsoleMessage message = new ConsoleMessage();
        message.setMessage("{\"operationId\":\"restart-test\"}");
        CallbackResult result = new CallbackResult();

        new RestartAppHandler().handle(message, new ConsoleResult(), result.callback());

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
