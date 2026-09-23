package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.enums.agent.McpTransport;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerState;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import ai.chat2db.community.domain.api.service.agent.IMcpServerService;
import ai.chat2db.community.domain.api.service.agent.IMcpServerStorage;
import ai.chat2db.community.domain.api.service.agent.IMcpToolDiscovery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Owns the configured MCP servers. Every change is written to the user file before it is reported, so
 * a failed connection never loses the configuration the user just approved.
 */
@Service
public class McpServerServiceImpl implements IMcpServerService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern DERIVED = Pattern.compile("[^a-z0-9_-]+");

    private final IMcpServerStorage storage;
    private final ObjectProvider<IMcpToolDiscovery> discovery;

    public McpServerServiceImpl(IMcpServerStorage storage, ObjectProvider<IMcpToolDiscovery> discovery) {
        this.storage = storage;
        this.discovery = discovery;
    }

    @Override
    public String configPath() {
        return storage.path();
    }

    @Override
    public synchronized List<McpServerState> list() {
        return storage.load().stream().map(McpServerState::from).toList();
    }

    @Override
    public synchronized McpServerConfig require(String name) {
        String normalized = normalizeLookup(name);
        return storage.load().stream().filter(config -> config.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist: " + name
                        + ". Call mcp_list_servers to see the configured names."));
    }

    @Override
    public synchronized McpServerState add(McpServerRegistration registration) {
        McpServerConfig created = build(registration, null);
        List<McpServerConfig> servers = new ArrayList<>(storage.load());
        if (servers.stream().anyMatch(config -> config.name().equals(created.name()))) {
            throw new IllegalArgumentException("MCP server already exists: " + created.name()
                    + ". Use mcp_update_server or another name.");
        }
        servers.add(created);
        storage.save(List.copyOf(servers));
        return McpServerState.from(created);
    }

    @Override
    public synchronized McpServerState update(String name, McpServerRegistration registration) {
        McpServerConfig existing = require(name);
        McpServerConfig updated = build(registration, existing);
        List<McpServerConfig> servers = new ArrayList<>(storage.load());
        servers.replaceAll(config -> config.name().equals(existing.name()) ? updated : config);
        storage.save(List.copyOf(servers));
        if (identityChanged(existing, updated)) {
            detach(existing);
            refreshToolsQuietly(updated.name());
            return McpServerState.from(require(updated.name()));
        }
        return McpServerState.from(updated);
    }

    @Override
    public synchronized void remove(String name) {
        McpServerConfig existing = require(name);
        List<McpServerConfig> servers = new ArrayList<>(storage.load());
        servers.removeIf(config -> config.name().equals(existing.name()));
        storage.save(List.copyOf(servers));
        detach(existing);
    }

    @Override
    public synchronized McpServerState setEnabled(String name, boolean enabled) {
        McpServerConfig existing = require(name);
        McpServerConfig updated = existing.withEnabled(enabled, now());
        replace(existing.name(), updated);
        if (!enabled) detach(existing);
        return McpServerState.from(updated);
    }

    @Override
    public synchronized McpServerState setPolicy(String name, McpToolPolicy policy, List<String> allowedTools) {
        McpServerConfig existing = require(name);
        McpToolPolicy next = policy == null ? existing.policy() : policy;
        List<String> remembered = allowedTools == null ? existing.allowedTools() : List.copyOf(allowedTools);
        McpServerConfig updated = existing.withDecision(next, remembered, now());
        replace(existing.name(), updated);
        return McpServerState.from(updated);
    }

    @Override
    public synchronized McpServerState refreshTools(String name) {
        McpServerConfig existing = require(name);
        IMcpToolDiscovery client = discovery.getIfAvailable();
        if (client == null) throw new IllegalStateException("MCP support is not available in this runtime");
        List<McpToolDescriptor> tools = client.discover(existing);
        McpServerConfig updated = existing.withTools(tools, now());
        replace(existing.name(), updated);
        return McpServerState.from(updated);
    }

    @Override
    public synchronized List<McpServerConfig> enabledServers() {
        return storage.load().stream().filter(McpServerConfig::enabled).toList();
    }

    @Override
    public synchronized void rememberTool(String name, String toolName) {
        McpServerConfig existing = require(name);
        if (existing.allowedTools().contains(toolName)) return;
        List<String> remembered = new ArrayList<>(existing.allowedTools());
        remembered.add(toolName);
        replace(existing.name(), existing.withDecision(existing.policy(), remembered, now()));
    }

    @Override
    public synchronized void rememberServer(String name) {
        McpServerConfig existing = require(name);
        replace(existing.name(), existing.withDecision(McpToolPolicy.ALLOW, existing.allowedTools(), now()));
    }

    @Override
    public boolean isToolAllowed(McpServerConfig config, String toolName) {
        return config.policy() == McpToolPolicy.ALLOW || config.allowedTools().contains(toolName);
    }

    @Override
    public String commandHash(McpServerConfig config) {
        String identity = String.join("\n", Objects.toString(config.transport(), ""),
                Objects.toString(config.commandLine(), ""), String.join(",", config.environmentKeys()),
                Objects.toString(config.url(), ""), String.join(",", config.headerNames()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    @Override
    public synchronized void approveCommand(String name, String commandHash) {
        McpServerConfig existing = require(name);
        McpServerConfig updated = new McpServerConfig(existing.name(), existing.transport(), existing.command(),
                existing.args(), existing.url(), existing.headerNames(), existing.environmentKeys(),
                existing.secrets(), existing.enabled(), existing.policy(), existing.allowedTools(),
                commandHash, existing.tools(), now());
        replace(existing.name(), updated);
    }

    @Override
    public boolean isCommandApproved(McpServerConfig config, String commandHash) {
        if (config.transport() != McpTransport.STDIO) return true;
        return commandHash.equals(config.approvedCommandHash());
    }

    private McpServerConfig build(McpServerRegistration registration, McpServerConfig existing) {
        if (registration == null) throw new IllegalArgumentException("A server definition is required");
        McpTransport transport = transport(registration, existing);
        String name = registration.name() != null && !registration.name().isBlank()
                ? validateName(registration.name())
                : existing != null ? existing.name() : deriveName(registration, transport);
        String command = transport == McpTransport.STDIO
                ? requireText(registration.command(), existing == null ? null : existing.command(),
                        "command is required for a stdio MCP server")
                : null;
        List<String> args = args(registration, existing);
        String url = transport == McpTransport.HTTP
                ? McpEndpointPolicy.validate(registration.url() != null ? registration.url()
                        : existing == null ? null : existing.url())
                : null;
        List<String> environmentKeys = transport == McpTransport.STDIO
                ? keys(registration.environmentKeys(), existing == null ? List.of() : existing.environmentKeys())
                : List.of();
        List<String> headerNames = transport == McpTransport.HTTP
                ? keys(registration.headerNames(), existing == null ? List.of() : existing.headerNames())
                : List.of();
        Map<String, String> secrets = secrets(registration.secrets(), existing);
        boolean enabled = registration.enabled() != null ? registration.enabled()
                : existing == null || existing.enabled();
        McpToolPolicy policy = registration.policy() != null ? registration.policy()
                : existing == null ? McpToolPolicy.ASK : existing.policy();
        String commandLine = transport == McpTransport.STDIO && command != null
                ? (args.isEmpty() ? command : command + " " + String.join(" ", args)) : null;
        boolean sameIdentity = existing != null
                && existing.transport() == transport
                && Objects.equals(existing.commandLine(), commandLine)
                && Objects.equals(existing.url(), url)
                && Objects.equals(existing.environmentKeys(), environmentKeys)
                && Objects.equals(existing.headerNames(), headerNames);
        // Anything that changes what is launched or where it connects invalidates both the approved
        // launch line and the cached tool list; rotating a secret does not.
        String approved = sameIdentity ? existing.approvedCommandHash() : null;
        List<McpToolDescriptor> tools = sameIdentity ? existing.tools() : List.of();
        List<String> allowedTools = existing == null ? List.of() : existing.allowedTools();
        return new McpServerConfig(name, transport, command, args, url, headerNames, environmentKeys, secrets,
                enabled, policy, allowedTools, approved, tools, now());
    }

    private McpTransport transport(McpServerRegistration registration, McpServerConfig existing) {
        String requested = registration.transport();
        if (requested == null || requested.isBlank()) {
            if (existing != null) return existing.transport();
            return registration.url() != null && !registration.url().isBlank() ? McpTransport.HTTP : McpTransport.STDIO;
        }
        try {
            return McpTransport.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("transport must be stdio or http");
        }
    }

    private List<String> args(McpServerRegistration registration, McpServerConfig existing) {
        if (registration.args() != null) return List.copyOf(registration.args());
        return existing == null ? List.of() : existing.args();
    }

    private static String requireText(String value, String fallback, String message) {
        String resolved = value != null && !value.isBlank() ? value.trim() : fallback;
        if (resolved == null || resolved.isBlank()) throw new IllegalArgumentException(message);
        return resolved;
    }

    private static String validateName(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "name must match [a-z0-9][a-z0-9_-]{0,31}; use a short lowercase name");
        }
        return normalized;
    }

    private static String normalizeLookup(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String deriveName(McpServerRegistration registration, McpTransport transport) {
        String source = transport == McpTransport.STDIO
                ? registration.command()
                : registration.url() == null ? null : java.net.URI.create(registration.url().trim()).getHost();
        if (source == null || source.isBlank()) throw new IllegalArgumentException("name is required");
        String candidate = source.replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0) candidate = candidate.substring(slash + 1);
        int dot = candidate.indexOf('.');
        if (dot > 0) candidate = candidate.substring(0, dot);
        String derived = DERIVED.matcher(candidate.toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (derived.isEmpty()) throw new IllegalArgumentException("name is required");
        return validateName(derived.length() > 32 ? derived.substring(0, 32) : derived);
    }

    private static List<String> keys(List<String> requested, List<String> fallback) {
        if (requested == null) return List.copyOf(fallback);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String key : requested) {
            if (key == null || key.isBlank()) continue;
            String trimmed = key.trim();
            if (!trimmed.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("invalid name '" + trimmed + "'; use letters, digits, '_' or '-'");
            }
            keys.add(trimmed);
        }
        return List.copyOf(keys);
    }

    private static Map<String, String> secrets(Map<String, String> requested, McpServerConfig existing) {
        Map<String, String> base = existing == null ? Map.of() : existing.secrets();
        if (requested == null) return base;
        Map<String, String> merged = new java.util.LinkedHashMap<>(base);
        requested.forEach((key, value) -> {
            if (value == null || value.isBlank()) merged.remove(key);
            else merged.put(key, value);
        });
        return Map.copyOf(merged);
    }

    private static boolean identityChanged(McpServerConfig before, McpServerConfig after) {
        return before.transport() != after.transport()
                || !Objects.equals(before.commandLine(), after.commandLine())
                || !Objects.equals(before.url(), after.url())
                || !Objects.equals(before.environmentKeys(), after.environmentKeys())
                || !Objects.equals(before.headerNames(), after.headerNames());
    }

    private void replace(String name, McpServerConfig updated) {
        List<McpServerConfig> servers = new ArrayList<>(storage.load());
        servers.replaceAll(config -> config.name().equals(name) ? updated : config);
        storage.save(List.copyOf(servers));
    }

    private void detach(McpServerConfig config) {
        IMcpToolDiscovery client = discovery.getIfAvailable();
        if (client == null) return;
        try {
            client.close(config.name());
        } catch (RuntimeException ignored) {
            // A connection that is already gone must not block a configuration change.
        }
    }

    private void refreshToolsQuietly(String name) {
        try {
            refreshTools(name);
        } catch (RuntimeException error) {
            // The configuration is stored; the next mcp_test_server call reports the real error.
        }
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
