package ai.chat2db.community.updater.v2.transport;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

public final class HttpsUpdateTransport implements UpdateTransport {

    private final HttpClient client;
    private final java.util.function.Predicate<URI> allowedUrl;

    public HttpsUpdateTransport() {
        this(null);
    }

    public HttpsUpdateTransport(java.util.function.Predicate<URI> allowedUrl) {
        this.allowedUrl = allowedUrl;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(allowedUrl == null ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
            .build();
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public <T> T getJson(String url, Class<T> type) {
        try {
            HttpResponse<InputStream> response = send(url);
            requireSuccessfulHttpsResponse(response, url);
            try (InputStream body = response.body()) {
                return objectMapper.readValue(body, type);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot fetch update metadata: " + url, exception);
        }
    }

    @Override
    public Path download(String url, Path destination, long expectedSize, String expectedSha256,
            DownloadProgress progress) {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            Files.createDirectories(destination.getParent());
            HttpResponse<InputStream> response = send(url);
            requireSuccessfulHttpsResponse(response, url);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long downloaded = 0L;
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloaded += read;
                    progress.onBytes(downloaded, expectedSize);
                }
            }
            if (downloaded != expectedSize) {
                throw new IllegalStateException("Update payload size mismatch: expected " + expectedSize
                    + ", got " + downloaded);
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException("Update payload SHA-256 mismatch");
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot download update payload: " + url, exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception ignored) {
            }
        }
    }

    private HttpResponse<InputStream> send(String url) throws Exception {
        URI current = URI.create(url);
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (allowedUrl != null && !allowedUrl.test(current)) {
                throw new IllegalArgumentException("Update source URL is not allowed: " + current.getHost());
            }
            HttpResponse<InputStream> response = client.send(request(current.toString()),
                HttpResponse.BodyHandlers.ofInputStream());
            if (allowedUrl == null || !java.util.Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                return response;
            }
            try (InputStream ignored = response.body()) {
                current = current.resolve(response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Update redirect is missing Location")));
            }
        }
        throw new IllegalStateException("Too many update redirects");
    }

    private static HttpRequest request(String url) {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Update URL must use HTTPS: " + url);
        }
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", "Chat2DB-Updater/2")
            .header("Referer", "https://chat2db.ai")
            .GET()
            .build();
    }

    private static void requireSuccessfulHttpsResponse(HttpResponse<?> response, String requestedUrl) {
        URI finalUri = response.uri();
        if (!"https".equalsIgnoreCase(finalUri.getScheme())) {
            throw new IllegalStateException("Update request redirected away from HTTPS: " + requestedUrl);
        }
        if (response.statusCode() == 404) {
            throw new MissingUpdateResourceException(
                "Update server returned HTTP " + response.statusCode());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Update server returned HTTP " + response.statusCode());
        }
    }

    /**
     * A release index or manifest that the update source does not publish. A channel that has not
     * published its index yet is not a failure the user can act on, unlike a timeout or a server
     * error, so the update check reports the two cases differently.
     */
    public static final class MissingUpdateResourceException extends IllegalStateException {

        public MissingUpdateResourceException(String message) {
            super(message);
        }
    }

    /** Whether the failure is only a missing release index or manifest. */
    public static boolean isMissingResource(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MissingUpdateResourceException) {
                return true;
            }
        }
        return false;
    }
}
