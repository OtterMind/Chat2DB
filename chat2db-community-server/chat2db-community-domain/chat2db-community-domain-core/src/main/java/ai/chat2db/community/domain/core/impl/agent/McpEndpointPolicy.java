package ai.chat2db.community.domain.core.impl.agent;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Validates where an external MCP server may live. Local servers are a normal setup, so loopback is
 * allowed over plain HTTP; anything else must use HTTPS, and link-local or metadata addresses are
 * rejected because the backend, not the browser, would be the one connecting to them.
 */
final class McpEndpointPolicy {

    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata.google.internal", "metadata.goog", "instance-data");

    private McpEndpointPolicy() {
    }

    static String validate(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required for an HTTP MCP server");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("url must use http or https");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("url must not contain credentials");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("url must contain a host");
        if (METADATA_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("url must not target a cloud metadata endpoint");
        }
        boolean loopback = isLoopback(host);
        if ("http".equals(scheme) && !loopback) {
            throw new IllegalArgumentException("url must use https unless it points at localhost");
        }
        if (!loopback && isBlocked(host)) {
            throw new IllegalArgumentException("url must not target a link-local or unspecified address");
        }
        return uri.toString();
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || "::1".equals(normalized) || "[::1]".equals(normalized)) return true;
        for (InetAddress address : resolve(host)) {
            if (address.isLoopbackAddress()) return true;
        }
        return false;
    }

    private static boolean isBlocked(String host) {
        for (InetAddress address : resolve(host)) {
            if (address.isLinkLocalAddress() || address.isAnyLocalAddress()) return true;
        }
        return false;
    }

    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException error) {
            // An unresolvable host is reported when the server is actually used.
            return new InetAddress[0];
        }
    }
}
