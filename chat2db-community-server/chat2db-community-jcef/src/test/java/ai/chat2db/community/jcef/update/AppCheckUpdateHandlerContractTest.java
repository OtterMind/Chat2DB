package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.handler.biz.update.AppCheckUpdateHandler;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppCheckUpdateHandlerContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void resetUpdaterRegistry() {
        DesktopUpdaterRegistry.resetForTests();
    }

    @Test
    void forwardsFailureRecoveryFieldsToTheJcefResponse() throws Exception {
        String releasePage = "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0";
        DesktopUpdaterRegistry.register(new StaticDesktopUpdater(
                DesktopUpdateCheckResult.failed(releasePage, UpdateFailureStage.CHECK, UpdateFailureReason.NETWORK)));

        JsonNode data = invokeHandler();

        assertEquals("updateFailed", data.path("status").asText());
        assertEquals(releasePage, data.path("releasePageUrl").asText());
        assertEquals("CHECK", data.path("failureStage").asText());
        assertEquals("NETWORK", data.path("failureReason").asText());
    }

    @Test
    void forwardsAvailableReleaseFieldsWithoutInventingFailureValues() throws Exception {
        String releasePage = "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0";
        DesktopUpdaterRegistry.register(new StaticDesktopUpdater(
                DesktopUpdateCheckResult.available("5.4.0", "Security fixes", releasePage)));

        JsonNode data = invokeHandler();

        assertEquals("available", data.path("status").asText());
        assertEquals("5.4.0", data.path("version").asText());
        assertEquals("Security fixes", data.path("releaseNotes").asText());
        assertEquals(releasePage, data.path("releasePageUrl").asText());
        assertEquals(false, data.has("failureStage"));
        assertEquals(false, data.has("failureReason"));
    }

    private static JsonNode invokeHandler() throws Exception {
        AtomicReference<String> response = new AtomicReference<>();
        new AppCheckUpdateHandler().handle(null, null, new CefQueryCallback() {
            @Override
            public void success(String value) {
                response.set(value);
            }

            @Override
            public void failure(int errorCode, String errorMessage) {
                throw new AssertionError("Unexpected JCEF failure: " + errorCode + " " + errorMessage);
            }
        });
        return OBJECT_MAPPER.readTree(response.get()).path("data");
    }

    private record StaticDesktopUpdater(DesktopUpdateCheckResult result) implements IDesktopUpdater {
        @Override
        public DesktopUpdateCheckResult appCheckUpdate() {
            return result;
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
