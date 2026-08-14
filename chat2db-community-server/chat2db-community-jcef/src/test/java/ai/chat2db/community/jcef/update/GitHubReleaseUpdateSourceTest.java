package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseUpdateSourceTest {

    private static final URI LATEST_URI = URI.create(
            "https://github.com/OtterMind/Chat2DB/releases/latest/download/version.json");
    private static final URI VERSIONED_URI = URI.create(
            "https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/version.json");
    private static final URI PAYLOAD_URI = URI.create(
            "https://github.com/OtterMind/Chat2DB/releases/download/v5.4.0/chat2db-community.jar");
    private static final URI DELEGATED_URI = URI.create(
            "https://objects.githubusercontent.com/abc?sig=xyz");

    private static final AddressValidator LENIENT_ADDRESS_VALIDATOR = host -> {
        if (host == null || host.isEmpty()) {
            throw new IOException("missing host");
        }
    };

    private static GitHubReleaseUpdateSource sourceFor(Map<String, MockHttpURLConnection> responses) {
        ConnectionOpener opener = uri -> {
            MockHttpURLConnection connection = responses.get(uri.toString());
            if (connection == null) {
                throw new IOException("Unexpected URI: " + uri);
            }
            return connection;
        };
        return new GitHubReleaseUpdateSource("5.3.0", opener, LENIENT_ADDRESS_VALIDATOR);
    }

    private static String manifest(boolean forceUpdate, String releasePageUrl) {
        return """
                {
                  "version": "5.4.0",
                  "releaseNotes": "Known issue fixes",
                  "releasePageUrl": "%s",
                  "forceUpdate": %b
                }
                """.formatted(releasePageUrl, forceUpdate);
    }

    private static String manifestWithoutForceUpdate() {
        return """
                {
                  "version": "5.4.0",
                  "releaseNotes": "Known issue fixes",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0"
                }
                """;
    }

    @Test
    void fetchLatestManifestParsesBasicFields() throws IOException {
        String json = manifest(false, "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0");
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 200).withBody(json.getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        FetchedUpdateManifest result = source.fetchLatestManifest();

        assertEquals("5.4.0", result.version());
        assertEquals("Known issue fixes", result.releaseNotes());
        assertEquals("https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0", result.releasePageUrl());
        assertEquals(Boolean.FALSE, result.forceUpdate());
        assertTrue(result.fetchedAtNanos() > 0);
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), result.exactBytes());
    }

    @Test
    void fetchLatestManifestFollowsGithubRedirectToVersionedAsset() throws IOException {
        String json = manifest(false, "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0");
        Map<String, MockHttpURLConnection> responses = new HashMap<>();
        responses.put(LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 302).withHeader("Location", VERSIONED_URI.toString()));
        responses.put(VERSIONED_URI.toString(),
                new MockHttpURLConnection(VERSIONED_URI, 200).withBody(json.getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        FetchedUpdateManifest result = source.fetchLatestManifest();

        assertEquals("5.4.0", result.version());
    }

    @Test
    void fetchLatestManifestRejectsForceUpdateTrue() {
        String json = manifest(true, "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0");
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 200).withBody(json.getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void fetchLatestManifestRejectsMissingForceUpdate() {
        String json = manifestWithoutForceUpdate();
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 200).withBody(json.getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void fetchLatestManifestRejectsVersionWithVPrefix() {
        String json = """
                {
                  "version": "v5.4.0",
                  "releaseNotes": "",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0",
                  "forceUpdate": false
                }
                """;
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 200).withBody(json.getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void fetchLatestManifestRejectsMismatchedReleasePageUrl() {
        String json = manifest(false, "https://github.com/Evil/Chat2DB/releases/tag/v5.4.0");
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 200).withBody(json.getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void fetchLatestManifestRejectsOversizedContentLength() {
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 200) {
                    @Override
                    public long getContentLengthLong() {
                        return 2 * 1024 * 1024;
                    }
                }.withBody("{}".getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void fetchLatestManifestRejectsOversizedBody() {
        byte[] body = new byte[1024 * 1024 + 1];
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(),
                new MockHttpURLConnection(LATEST_URI, 200).withBody(body));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void fetchLatestManifestRejectsNonOkStatus() {
        Map<String, MockHttpURLConnection> responses = Map.of(
                LATEST_URI.toString(), new MockHttpURLConnection(LATEST_URI, 503));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        assertThrows(IOException.class, source::fetchLatestManifest);
    }

    @Test
    void openPayloadReturnsStreamingResponse() throws IOException {
        byte[] payload = "jar-payload".getBytes(StandardCharsets.UTF_8);
        Map<String, MockHttpURLConnection> responses = Map.of(
                PAYLOAD_URI.toString(),
                new MockHttpURLConnection(PAYLOAD_URI, 200).withBody(payload));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.4.0", "chat2db-community.jar", PAYLOAD_URI, 0);
        try (UpdateResponse response = source.openPayload(request)) {
            assertEquals(200, response.statusCode());
            assertEquals(payload.length, response.contentLengthOrMinusOne());
            assertArrayEquals(payload, response.openStream().readAllBytes());
        }
    }

    @Test
    void openPayloadAcceptsPartialResponse() throws IOException {
        byte[] payload = "jar-payload".getBytes(StandardCharsets.UTF_8);
        Map<String, MockHttpURLConnection> responses = Map.of(
                PAYLOAD_URI.toString(),
                new MockHttpURLConnection(PAYLOAD_URI, 206).withBody(payload));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.4.0", "chat2db-community.jar", PAYLOAD_URI, 3);
        try (UpdateResponse response = source.openPayload(request)) {
            assertEquals(206, response.statusCode());
        }
    }

    @Test
    void openPayloadRejectsUnexpectedStatus() {
        Map<String, MockHttpURLConnection> responses = Map.of(
                PAYLOAD_URI.toString(), new MockHttpURLConnection(PAYLOAD_URI, 404));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.4.0", "chat2db-community.jar", PAYLOAD_URI, 0);
        assertThrows(IOException.class, () -> source.openPayload(request));
    }

    @Test
    void openPayloadFollowsDelegatedRedirect() throws IOException {
        byte[] payload = "delegated-payload".getBytes(StandardCharsets.UTF_8);
        Map<String, MockHttpURLConnection> responses = new HashMap<>();
        responses.put(PAYLOAD_URI.toString(),
                new MockHttpURLConnection(PAYLOAD_URI, 302).withHeader("Location", DELEGATED_URI.toString()));
        responses.put(DELEGATED_URI.toString(),
                new MockHttpURLConnection(DELEGATED_URI, 200).withBody(payload));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.4.0", "chat2db-community.jar", PAYLOAD_URI, 0);
        try (UpdateResponse response = source.openPayload(request)) {
            assertEquals(200, response.statusCode());
            assertArrayEquals(payload, response.openStream().readAllBytes());
        }
    }

    @Test
    void openPayloadRejectsRedirectToSecondOrigin() {
        URI firstDelegated = URI.create("https://objects.githubusercontent.com/abc");
        URI secondOrigin = URI.create("https://evil.example.com/payload");
        Map<String, MockHttpURLConnection> responses = new HashMap<>();
        responses.put(PAYLOAD_URI.toString(),
                new MockHttpURLConnection(PAYLOAD_URI, 302).withHeader("Location", firstDelegated.toString()));
        responses.put(firstDelegated.toString(),
                new MockHttpURLConnection(firstDelegated, 302).withHeader("Location", secondOrigin.toString()));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.4.0", "chat2db-community.jar", PAYLOAD_URI, 0);
        assertThrows(IOException.class, () -> source.openPayload(request));
    }

    @Test
    void openPayloadRejectsInvalidInitialUrl() {
        URI invalid = URI.create("https://evil.example.com/payload");
        Map<String, MockHttpURLConnection> responses = Map.of();

        GitHubReleaseUpdateSource source = sourceFor(responses);
        ValidatedPayloadRequest request = new ValidatedPayloadRequest("5.4.0", "chat2db-community.jar", invalid, 0);
        assertThrows(IOException.class, () -> source.openPayload(request));
    }

    @Test
    void userAgentIncludesLocalVersionWhenAvailable() throws IOException {
        String json = manifest(false, "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0");
        Map<String, MockHttpURLConnection> responses = new HashMap<>();
        responses.put(LATEST_URI.toString(), new MockHttpURLConnection(LATEST_URI, 200) {
            @Override
            public void setRequestProperty(String key, String value) {
                if ("User-Agent".equalsIgnoreCase(key)) {
                    assertEquals("Chat2DB-Community-Updater/5.3.0", value);
                }
                super.setRequestProperty(key, value);
            }
        }.withBody(json.getBytes(StandardCharsets.UTF_8)));

        GitHubReleaseUpdateSource source = sourceFor(responses);
        source.fetchLatestManifest();
    }
}
