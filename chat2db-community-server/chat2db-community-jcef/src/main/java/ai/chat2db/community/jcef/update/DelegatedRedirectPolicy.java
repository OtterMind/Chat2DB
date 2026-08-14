package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.net.URI;

/**
 * Delegated-origin redirect policy for GitHub Release asset downloads.
 *
 * <p>Only a {@code Location} returned by a validated {@code github.com} HTTPS response may
 * establish a delegated origin. After delegation, redirects may stay on that exact origin
 * or return to a trusted GitHub Release path. A second unrelated origin is rejected.
 */
final class DelegatedRedirectPolicy {

    static final int MAX_REDIRECTS = 5;

    private final URI initialUri;
    private int redirectCount;
    private URI delegatedOrigin;

    DelegatedRedirectPolicy(URI initialUri) {
        this.initialUri = initialUri;
    }

    URI nextUri(URI currentUri, String location) throws IOException {
        if (++redirectCount > MAX_REDIRECTS) {
            throw new IOException("Too many redirects");
        }
        if (location == null || location.isEmpty()) {
            throw new IOException("Redirect missing Location header");
        }
        URI nextUri;
        try {
            nextUri = currentUri.resolve(location).normalize();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid redirect location", e);
        }
        validateRedirect(nextUri);
        return nextUri;
    }

    private void validateRedirect(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Redirect must use HTTPS");
        }
        if (uri.getPort() != -1) {
            throw new IOException("Redirect must use the default HTTPS port");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("Redirect must not contain userinfo");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Redirect host is missing");
        }
        if (NetworkAddressPolicy.isIpLiteral(host)) {
            throw new IOException("Redirect host must not be an IP literal");
        }

        boolean isTrusted = isTrustedGithubPath(uri);
        if (isTrusted) {
            if (uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("Trusted GitHub path must not contain a query or fragment");
            }
            return;
        }

        URI origin = originOf(uri);
        if (delegatedOrigin == null) {
            delegatedOrigin = origin;
        } else if (!delegatedOrigin.equals(origin)) {
            throw new IOException("Redirect to a second unrelated origin is not allowed");
        }
    }

    private static URI originOf(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build origin URI", e);
        }
    }

    private static boolean isTrustedGithubPath(URI uri) {
        if (!UpdateUrlPolicy.GITHUB_HOST.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        if (UpdateUrlPolicy.LATEST_PATH.equals(path)) {
            return true;
        }
        String prefix = UpdateUrlPolicy.REPO_PATH + "/releases/download/v";
        if (!path.startsWith(prefix)) {
            return false;
        }
        String versionAndAsset = path.substring(prefix.length());
        int slash = versionAndAsset.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String version = versionAndAsset.substring(0, slash);
        String asset = versionAndAsset.substring(slash + 1);
        return !version.isEmpty()
                && !asset.isEmpty()
                && asset.indexOf('/') < 0
                && asset.indexOf('\\') < 0;
    }
}
