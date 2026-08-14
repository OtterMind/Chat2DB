package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestUpdateSourceTest {

    @Test
    void returnsConfiguredManifest() {
        byte[] bytes = "{}".getBytes();
        FetchedUpdateManifest manifest = new FetchedUpdateManifest(bytes, "5.4.0", null, null, Boolean.FALSE, 1L);
        TestUpdateSource source = new TestUpdateSource(manifest, Map.of());

        FetchedUpdateManifest result = source.fetchLatestManifest();

        assertEquals("5.4.0", result.version());
        assertArrayEquals(bytes, result.exactBytes());
    }

    @Test
    void streamsConfiguredAsset(@TempDir Path tempDir) throws IOException {
        Path asset = tempDir.resolve("chat2db-community.jar");
        Files.writeString(asset, "test-payload");
        FetchedUpdateManifest manifest = new FetchedUpdateManifest(
                "{}".getBytes(), "5.4.0", null, null, Boolean.FALSE, 1L);
        TestUpdateSource source = new TestUpdateSource(manifest, Map.of("chat2db-community.jar", asset));

        ValidatedPayloadRequest request = new ValidatedPayloadRequest(
                "5.4.0", "chat2db-community.jar", URI.create("http://test.local/chat2db-community.jar"), 0);
        try (UpdateResponse response = source.openPayload(request)) {
            assertEquals(200, response.statusCode());
            assertEquals(Files.size(asset), response.contentLengthOrMinusOne());
            assertArrayEquals(Files.readAllBytes(asset), response.openStream().readAllBytes());
        }
    }

    @Test
    void throwsForMissingAsset() {
        FetchedUpdateManifest manifest = new FetchedUpdateManifest(
                "{}".getBytes(), "5.4.0", null, null, Boolean.FALSE, 1L);
        TestUpdateSource source = new TestUpdateSource(manifest, Map.of());

        ValidatedPayloadRequest request = new ValidatedPayloadRequest(
                "5.4.0", "chat2db-community.jar", URI.create("http://test.local/chat2db-community.jar"), 0);
        assertThrows(IOException.class, () -> source.openPayload(request));
    }
}
