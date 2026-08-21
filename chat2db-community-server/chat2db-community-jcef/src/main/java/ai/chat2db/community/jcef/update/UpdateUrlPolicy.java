package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.net.URI;

/**
 * Exact URL policy for the GitHub Release update source.
 */
final class UpdateUrlPolicy {

    static final String GITHUB_HOST = "github.com";
    static final String REPO_PATH = "/OtterMind/Chat2DB";
    static final String LATEST_PATH = REPO_PATH + "/releases/latest/download/latest_version.json";

    private UpdateUrlPolicy() {
    }

    static void validateLatestUrl(URI uri) throws IOException {
        validateCommon(uri);
        if (!GITHUB_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Latest manifest URL host must be github.com");
        }
        if (!LATEST_PATH.equals(uri.getPath())) {
            throw new IOException("Latest manifest URL path is not allowed");
        }
        if (uri.getQuery() != null) {
            throw new IOException("Latest manifest URL must not contain a query");
        }
        if (uri.getFragment() != null) {
            throw new IOException("Latest manifest URL must not contain a fragment");
        }
    }

    static void validatePayloadUrl(URI uri, String expectedVersion, String expectedAssetName) throws IOException {
        validateCommon(uri);
        if (!GITHUB_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Payload URL host must be github.com");
        }
        if (uri.getQuery() != null) {
            throw new IOException("Payload URL must not contain a query");
        }
        if (uri.getFragment() != null) {
            throw new IOException("Payload URL must not contain a fragment");
        }
        String path = uri.getPath();
        if (path == null) {
            throw new IOException("Payload URL path is missing");
        }
        String expectedPrefix = REPO_PATH + "/releases/download/v" + expectedVersion + "/";
        if (!path.startsWith(expectedPrefix)) {
            throw new IOException("Payload URL path does not match the expected release");
        }
        String assetPart = path.substring(expectedPrefix.length());
        if (assetPart.isEmpty() || assetPart.indexOf('/') >= 0 || assetPart.indexOf('\\') >= 0) {
            throw new IOException("Payload URL asset name is not allowed");
        }
        if (!assetPart.equals(expectedAssetName)) {
            throw new IOException("Payload URL asset name does not match request");
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null) {
            String lower = rawPath.toLowerCase();
            if (lower.contains("%2f") || lower.contains("%5c")) {
                throw new IOException("Payload URL contains an encoded path separator");
            }
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Payload URL contains a relative path segment");
            }
        }
    }

    static void validateVersionManifestUrl(URI uri, String expectedVersion) throws IOException {
        validatePayloadUrl(uri, expectedVersion, "version.json");
    }

    private static void validateCommon(URI uri) throws IOException {
        if (uri == null) {
            throw new IOException("URL is null");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("URL must use HTTPS");
        }
        if (uri.getPort() != -1) {
            throw new IOException("URL must use the default HTTPS port");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("URL must not contain userinfo");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("URL host is missing");
        }
        if (NetworkAddressPolicy.isIpLiteral(host)) {
            throw new IOException("URL host must not be an IP literal");
        }
    }
}
