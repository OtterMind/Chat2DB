package ai.chat2db.community.start.ai.subscription.appserver;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime-injected dedicated MCP bridge endpoint for the supervised app-server process.
 * <p>
 * Capability material is never written to {@code config.toml}; only a localhost URL and the
 * environment variable <em>name</em> used to populate the capability header are persisted.
 * The raw capability value is supplied only via the process environment.
 */
public final class AppServerMcpEndpoint {

    public static final String DEFAULT_CAPABILITY_HEADER =
            "X-Chat2DB-MCP-Capability";
    public static final String DEFAULT_CAPABILITY_ENV_VAR =
            "CHAT2DB_MCP_CAPABILITY";

    private final String url;
    private final String capabilityEnvVarName;
    private final String capabilityHeaderName;
    private final String capabilityValue;

    public AppServerMcpEndpoint(String url, String capabilityEnvVarName, String capabilityValue) {
        this(url, capabilityEnvVarName, DEFAULT_CAPABILITY_HEADER, capabilityValue);
    }

    public AppServerMcpEndpoint(
            String url,
            String capabilityEnvVarName,
            String capabilityHeaderName,
            String capabilityValue) {
        this.url = requireLoopbackHttpUrl(url);
        this.capabilityEnvVarName = requireEnvName(capabilityEnvVarName, "capabilityEnvVarName");
        this.capabilityHeaderName = Objects.requireNonNull(capabilityHeaderName, "capabilityHeaderName").trim();
        if (this.capabilityHeaderName.isEmpty()) {
            throw new IllegalArgumentException("capabilityHeaderName must not be blank");
        }
        this.capabilityValue = Objects.requireNonNull(capabilityValue, "capabilityValue");
        if (this.capabilityValue.isBlank()) {
            throw new IllegalArgumentException("capabilityValue must not be blank");
        }
    }

    public String url() {
        return url;
    }

    public String capabilityEnvVarName() {
        return capabilityEnvVarName;
    }

    public String capabilityHeaderName() {
        return capabilityHeaderName;
    }

    public String capabilityValue() {
        return capabilityValue;
    }

    private static String requireLoopbackHttpUrl(String raw) {
        Objects.requireNonNull(raw, "url");
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("mcp url must be a valid URI", ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("mcp url must use http or https");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("mcp url must include a host");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (!"127.0.0.1".equals(normalized) && !"localhost".equals(normalized)) {
            throw new IllegalArgumentException("mcp url host must be 127.0.0.1 (loopback only)");
        }
        // Canonicalize localhost to 127.0.0.1 for config persistence.
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        int port = uri.getPort();
        String authority = port < 0 ? "127.0.0.1" : "127.0.0.1:" + port;
        return scheme + "://" + authority + path + query;
    }

    private static String requireEnvName(String name, String label) {
        Objects.requireNonNull(name, label);
        String trimmed = name.trim();
        if (!trimmed.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(label + " must be a valid environment variable name");
        }
        return trimmed;
    }
}
