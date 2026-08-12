package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterDownloadResumeTest {

    @Test
    void acceptsMatchingPartialContentForTheSavedOffset() {
        assertTrue(Updater.isPartialResponseForOffset(
                128L, 512L, HttpURLConnection.HTTP_PARTIAL, "bytes 128-511/512", 384L));
    }

    @Test
    void rejectsPartialContentThatDoesNotMatchTheSavedOffsetOrExpectedSize() {
        assertFalse(Updater.isPartialResponseForOffset(
                128L, 512L, HttpURLConnection.HTTP_PARTIAL, "bytes 0-511/512", 512L));
        assertFalse(Updater.isPartialResponseForOffset(
                128L, 512L, HttpURLConnection.HTTP_OK, null, 512L));
        assertFalse(Updater.isPartialResponseForOffset(
                128L, 512L, HttpURLConnection.HTTP_PARTIAL, "bytes 128-511/1024", 384L));
    }
}
