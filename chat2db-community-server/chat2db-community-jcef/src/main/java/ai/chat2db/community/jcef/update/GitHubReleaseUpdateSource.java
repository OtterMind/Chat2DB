package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Production {@link UpdateSource} that discovers manifests and payloads from the
 * {@code OtterMind/Chat2DB} GitHub Releases endpoint.
 *
 * <p>This source:
 * <ul>
 *   <li>uses the exact latest-asset URL for discovery and bypasses HTTP caches;</li>
 *   <li>fetches at most 1 MiB of manifest data;</li>
 *   <li>enforces the exact versioned payload URL shape;</li>
 *   <li>follows at most five redirects with a delegated-origin policy;</li>
 *   <li>streams payloads without buffering the full body.</li>
 * </ul>
 */
public final class GitHubReleaseUpdateSource implements UpdateSource {

    private static final String LATEST_MANIFEST_URL =
            "https://github.com/OtterMind/Chat2DB/releases/latest/download/version.json";
    private static final long MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final long MAX_RELEASE_NOTES_BYTES = 64 * 1024;
    private static final long CONNECT_TIMEOUT_MS = 15_000;
    private static final long MANIFEST_READ_TIMEOUT_MS = 30_000;
    private static final long PAYLOAD_READ_TIMEOUT_MS = 120_000;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]+)+$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String userAgent;
    private final ConnectionOpener connectionOpener;
    private final AddressValidator addressValidator;

    public GitHubReleaseUpdateSource() {
        this(null);
    }

    public GitHubReleaseUpdateSource(String localVersion) {
        this(localVersion, ConnectionOpener.DEFAULT, AddressValidator.STRICT);
    }

    GitHubReleaseUpdateSource(String localVersion, ConnectionOpener connectionOpener, AddressValidator addressValidator) {
        this.userAgent = buildUserAgent(localVersion);
        this.connectionOpener = connectionOpener;
        this.addressValidator = addressValidator;
    }

    private static String buildUserAgent(String localVersion) {
        if (localVersion == null || localVersion.isBlank()) {
            return "Chat2DB-Community-Updater";
        }
        return "Chat2DB-Community-Updater/" + localVersion.trim();
    }

    @Override
    public FetchedUpdateManifest fetchLatestManifest() throws IOException {
        URI uri = URI.create(LATEST_MANIFEST_URL);
        UpdateUrlPolicy.validateLatestUrl(uri);
        HttpURLConnection connection = openConnection(uri, true, -1);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected status fetching latest manifest: " + status);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_MANIFEST_BYTES) {
                throw new IOException("Manifest Content-Length exceeds " + MAX_MANIFEST_BYTES + " bytes");
            }
            byte[] bytes = readBounded(connection.getInputStream(), MAX_MANIFEST_BYTES);
            return parseManifest(bytes);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public UpdateResponse openPayload(ValidatedPayloadRequest request) throws IOException {
        URI uri = request.validatedUri();
        UpdateUrlPolicy.validatePayloadUrl(uri, request.version(), request.assetName());
        HttpURLConnection connection = openConnection(uri, false, request.rangeOffset());
        int status;
        try {
            status = connection.getResponseCode();
        } catch (IOException e) {
            connection.disconnect();
            throw e;
        }
        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IOException("Unexpected payload status: " + status);
        }
        return new HttpUpdateResponse(connection, status);
    }

    private HttpURLConnection openConnection(URI initialUri, boolean cacheBypass, long rangeOffset) throws IOException {
        DelegatedRedirectPolicy redirectPolicy = new DelegatedRedirectPolicy(initialUri);
        URI currentUri = initialUri;
        for (int hop = 0; hop <= DelegatedRedirectPolicy.MAX_REDIRECTS; hop++) {
            addressValidator.validate(currentUri.getHost());
            HttpURLConnection connection = connectionOpener.open(currentUri);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
            connection.setReadTimeout((int) (cacheBypass ? MANIFEST_READ_TIMEOUT_MS : PAYLOAD_READ_TIMEOUT_MS));
            connection.setRequestProperty("User-Agent", userAgent);
            if (cacheBypass) {
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");
            }
            if (rangeOffset > 0) {
                connection.setRequestProperty("Range", "bytes=" + rangeOffset + "-");
            }
            int responseCode;
            try {
                responseCode = connection.getResponseCode();
            } catch (IOException e) {
                connection.disconnect();
                throw e;
            }
            if (responseCode < HttpURLConnection.HTTP_MULT_CHOICE || responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            currentUri = redirectPolicy.nextUri(currentUri, location);
        }
        throw new IOException("Redirect limit exceeded");
    }

    private FetchedUpdateManifest parseManifest(byte[] bytes) throws IOException {
        DiscoveryManifest manifest = OBJECT_MAPPER.readValue(bytes, DiscoveryManifest.class);
        if (manifest == null) {
            throw new IOException("Manifest is empty");
        }
        if (manifest.version == null || manifest.version.isBlank()) {
            throw new IOException("Manifest version is missing");
        }
        String version = manifest.version.trim();
        if (version.startsWith("v") || version.startsWith("V")) {
            throw new IOException("Manifest version must not have a v prefix");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new IOException("Manifest version is not a numeric version");
        }
        if (manifest.forceUpdate == null) {
            throw new IOException("Remote GitHub manifest must include forceUpdate=false");
        }
        if (manifest.forceUpdate) {
            throw new IOException("Remote GitHub manifest must not require forced updates");
        }
        if (manifest.releaseNotes != null) {
            byte[] notesBytes = manifest.releaseNotes.getBytes(StandardCharsets.UTF_8);
            if (notesBytes.length > MAX_RELEASE_NOTES_BYTES) {
                throw new IOException("Manifest releaseNotes exceeds " + MAX_RELEASE_NOTES_BYTES + " bytes");
            }
        }
        if (manifest.releasePageUrl != null) {
            String expected = "https://github.com/OtterMind/Chat2DB/releases/tag/v" + version;
            if (!expected.equals(manifest.releasePageUrl)) {
                throw new IOException("Manifest releasePageUrl does not match the expected release page");
            }
        }
        return new FetchedUpdateManifest(bytes, version, manifest.releaseNotes, manifest.releasePageUrl,
                Boolean.FALSE, System.nanoTime());
    }

    private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Response body exceeds the maximum allowed size");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static class DiscoveryManifest {
        public String version;
        public String releaseNotes;
        public String releasePageUrl;
        public Boolean forceUpdate;
    }
}
