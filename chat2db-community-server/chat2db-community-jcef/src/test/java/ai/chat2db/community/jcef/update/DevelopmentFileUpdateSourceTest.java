package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentFileUpdateSourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void updaterDetectsNewerVersionFromExplicitLocalDirectory() throws Exception {
        byte[] versionManifest = "{\"version\":\"5.3.3\",\"files\":[],\"forceUpdate\":false}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(temporaryDirectory.resolve("version.json"), versionManifest);
        Files.writeString(temporaryDirectory.resolve("latest_version.json"), latestManifest("5.3.3", versionManifest));
        Files.writeString(temporaryDirectory.resolve("local_version.json"),
                "{\"version\":\"5.3.0\",\"files\":[]}");

        DevelopmentFileUpdateSource source = new DevelopmentFileUpdateSource(temporaryDirectory);
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), System::nanoTime);

        Updater.CheckResult result = updater.appCheckUpdate();

        assertFalse(result.isCheckFailed());
        assertTrue(result.isNeedsUpdate());
        assertEquals("5.3.3", result.getAvailableSnapshot().version());
    }

    @Test
    void readsVersionManifestAndStreamsRequestedPayloadRange() throws Exception {
        byte[] versionManifest = "{\"version\":\"5.3.3\",\"files\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] payload = "desktop-payload".getBytes(StandardCharsets.UTF_8);
        Files.write(temporaryDirectory.resolve("version.json"), versionManifest);
        Files.write(temporaryDirectory.resolve("chat2db-community.jar"), payload);
        DevelopmentFileUpdateSource source = new DevelopmentFileUpdateSource(temporaryDirectory);

        assertArrayEquals(versionManifest, source.fetchVersionManifest("5.3.3"));
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.3.3", "chat2db-community.jar",
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.3.3/chat2db-community.jar"), 8);
        try (UpdateResponse response = source.openPayload(request)) {
            assertEquals(206, response.statusCode());
            assertEquals("bytes 8-14/15", response.header("Content-Range"));
            assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), response.openStream().readAllBytes());
        }
    }

    @Test
    void rejectsPathsOutsideConfiguredDirectory() {
        DevelopmentFileUpdateSource source = new DevelopmentFileUpdateSource(temporaryDirectory);
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.3.3", "../secret",
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.3.3/secret"), 0);

        assertThrows(IOException.class, () -> source.openPayload(request));
    }

    @Test
    void rejectsDiscoveryManifestTypeCoercion() throws IOException {
        Files.writeString(temporaryDirectory.resolve("latest_version.json"), """
                {
                  "version": 533,
                  "forceUpdate": "invalid",
                  "metadataSha256": 123
                }
                """);

        DevelopmentFileUpdateSource source = new DevelopmentFileUpdateSource(temporaryDirectory);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void rejectsOversizedDiscoveryAndVersionManifests() throws IOException {
        Files.write(temporaryDirectory.resolve("latest_version.json"), new byte[64 * 1024 + 1]);
        DevelopmentFileUpdateSource source = new DevelopmentFileUpdateSource(temporaryDirectory);
        assertThrows(IOException.class, source::fetchLatestManifest);

        Files.write(temporaryDirectory.resolve("version.json"), new byte[1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> source.fetchVersionManifest("5.3.3"));
    }

    @Test
    void rejectsInvalidRangeOffsets() throws Exception {
        Files.writeString(temporaryDirectory.resolve("chat2db-community.jar"), "payload");
        DevelopmentFileUpdateSource source = new DevelopmentFileUpdateSource(temporaryDirectory);
        URI uri = URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.3.3/chat2db-community.jar");
        assertThrows(IOException.class, () -> source.openPayload(new ValidatedPayloadRequest("5.3.3", "chat2db-community.jar", uri, -1)));
        assertThrows(IOException.class, () -> source.openPayload(new ValidatedPayloadRequest("5.3.3", "chat2db-community.jar", uri, 8)));
        assertThrows(IOException.class, () -> source.openPayload(new ValidatedPayloadRequest("5.3.3", "chat2db-community.jar", uri, 7)));
    }

    @Test
    void rejectsPayloadSymlinkEscapingConfiguredDirectory() throws Exception {
        Path outside = Files.createTempFile("chat2db-update-outside-", ".jar");
        Path link = temporaryDirectory.resolve("chat2db-community.jar");
        try {
            Files.createSymbolicLink(link, outside);
            DevelopmentFileUpdateSource source = new DevelopmentFileUpdateSource(temporaryDirectory);
            URI uri = URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.3.3/chat2db-community.jar");
            assertThrows(IOException.class, () -> source.openPayload(new ValidatedPayloadRequest("5.3.3", "chat2db-community.jar", uri, 0)));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    private static String latestManifest(String version, byte[] versionManifest) {
        return """
                {
                  "version": "%s",
                  "releaseNotes": "Development fixture",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v%s",
                  "metadataSha256": "%s",
                  "forceUpdate": false
                }
                """.formatted(version, version, sha256(versionManifest));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
