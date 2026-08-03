package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerAccountView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerLoginStartResult;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;
import ai.chat2db.community.start.ai.subscription.appserver.internal.AppServerHomeLayout;
import ai.chat2db.community.start.ai.subscription.appserver.internal.AppServerJsonRpcClient;
import ai.chat2db.community.start.ai.subscription.appserver.internal.BinaryIntegrityGate;
import ai.chat2db.community.start.ai.subscription.appserver.internal.JdkProcessLauncher;
import ai.chat2db.community.start.ai.subscription.appserver.internal.ManagedProcess;
import ai.chat2db.community.start.ai.subscription.appserver.internal.ProcessLauncher;
import ai.chat2db.community.start.ai.subscription.appserver.internal.StderrDrain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supervises one pinned ChatGPT app-server process after gates pass.
 * Remains disabled by default and never extracts OAuth tokens.
 */
public final class CodexAppServerSupervisor implements CodexAppServerPort {

    private static final Set<String> SUPPORTED_LOGIN_TYPES = Set.of("chatgpt", "chatgptDeviceCode");
    private static final Set<String> SAFE_PARENT_ENVIRONMENT = Set.of(
            "PATH", "Path", "HOME", "TMPDIR", "TEMP", "TMP",
            "LANG", "LC_ALL", "LC_CTYPE",
            "SystemRoot", "WINDIR", "APPDATA", "LOCALAPPDATA", "USERPROFILE",
            "DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR", "DISPLAY", "WAYLAND_DISPLAY",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
            "http_proxy", "https_proxy", "all_proxy", "no_proxy");

    private final AppServerSupervisorConfig config;
    private final KeyringAvailabilityProbe keyringProbe;
    private final ProcessLauncher processLauncher;
    private final ObjectMapper mapper;
    private final BinaryIntegrityGate binaryGate = new BinaryIntegrityGate();
    private final List<AppServerEventListener> externalListeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<AppServerDisabledReason> disabledReason =
            new AtomicReference<>(AppServerDisabledReason.FEATURE_DISABLED_BY_DEFAULT);

    private ManagedProcess process;
    private AppServerJsonRpcClient client;
    private StderrDrain stderrDrain;
    private volatile boolean started;

    public CodexAppServerSupervisor(
            AppServerSupervisorConfig config,
            KeyringAvailabilityProbe keyringProbe) {
        this(config, keyringProbe, new JdkProcessLauncher(), new ObjectMapper());
    }

    public CodexAppServerSupervisor(
            AppServerSupervisorConfig config,
            KeyringAvailabilityProbe keyringProbe,
            ProcessLauncher processLauncher,
            ObjectMapper mapper) {
        this.config = Objects.requireNonNull(config, "config");
        this.keyringProbe = Objects.requireNonNull(keyringProbe, "keyringProbe");
        this.processLauncher = Objects.requireNonNull(processLauncher, "processLauncher");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (!config.featureEnabled()) {
            disabledReason.set(AppServerDisabledReason.FEATURE_DISABLED_BY_DEFAULT);
        } else {
            disabledReason.set(AppServerDisabledReason.RUNTIME_GATES_NOT_SATISFIED);
        }
    }

    @Override
    public synchronized boolean isEnabled() {
        return started && client != null && process != null && process.isAlive()
                && disabledReason.get() == null;
    }

    @Override
    public Optional<AppServerDisabledReason> disabledReason() {
        if (isEnabled()) {
            return Optional.empty();
        }
        AppServerDisabledReason reason = disabledReason.get();
        return Optional.of(reason == null ? AppServerDisabledReason.PROCESS_NOT_RUNNING : reason);
    }

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        if (!config.featureEnabled()) {
            disable(AppServerDisabledReason.FEATURE_DISABLED_BY_DEFAULT, "feature disabled by default");
            throw new AppServerException(
                    AppServerDisabledReason.FEATURE_DISABLED_BY_DEFAULT,
                    "ChatGPT app-server supervisor is disabled by default");
        }
        if (!keyringProbe.isKeyringAvailable()) {
            disable(AppServerDisabledReason.KEYRING_UNAVAILABLE, "OS Keyring unavailable");
            throw new AppServerException(
                    AppServerDisabledReason.KEYRING_UNAVAILABLE,
                    "OS Keyring is required; file credential fallback is forbidden");
        }
        try {
            // Path + SHA-256 only. Version is proven later from initialize userAgent.
            binaryGate.verifyBinary(config.binarySpec());
        } catch (AppServerException ex) {
            disable(ex.reason(), ex.getMessage());
            throw ex;
        }
        // Release pin: packaging manifest label must match the configured pin.
        // Official initialize does not return protocolLabel; never treat a fake echo as proof.
        if (!config.expectedProtocolLabel().equals(config.binarySpec().requiredProtocolLabel())) {
            disable(AppServerDisabledReason.PROTOCOL_MISMATCH, "release protocol pin mismatch");
            throw new AppServerException(
                    AppServerDisabledReason.PROTOCOL_MISMATCH,
                    "release protocol pin does not match binary manifest pin");
        }

        AppServerHomeLayout home = new AppServerHomeLayout(config.codexHome(), config.workdir());
        try {
            // MCP capability never written to config.toml — only loopback URL + env var name.
            home.prepare(config.mcpEndpoint().orElse(null));
        } catch (AppServerException ex) {
            disable(ex.reason(), ex.getMessage());
            throw ex;
        }

        Map<String, String> env = sanitizedParentEnvironment(System.getenv());
        env.put("CODEX_HOME", config.codexHome().toAbsolutePath().toString());
        env.putIfAbsent("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        // CODEX_HOME isolates Codex configuration. HOME must keep the OS user identity so
        // macOS Security.framework can resolve the user's default Keychain.
        // The whitelist above deliberately excludes every provider credential variable.
        // Capability lives only in process env when a dedicated MCP endpoint is injected.
        config.mcpEndpoint().ifPresent(mcp ->
                env.put(mcp.capabilityEnvVarName(), mcp.capabilityValue()));
        // CodeMode is forced off so nested MCP under exec is never used; do not inject a host path.
        // Some Codex builds honor this env when seatbelt would otherwise block network.
        env.put("CODEX_SANDBOX_NETWORK_DISABLED", "0");

        try {
            process = processLauncher.start(
                    config.launchCommand(),
                    config.workdir().toAbsolutePath(),
                    env);
            stderrDrain = new StderrDrain(process.stderr());
            // Bound request waits so a dead/hung app-server cannot stall Spring lifecycle.
            client = new AppServerJsonRpcClient(
                    process.stdout(),
                    process.stdin(),
                    mapper,
                    AppServerProtocol.DEFAULT_MAX_MESSAGE_BYTES,
                    5_000L);
            client.addListener(this::dispatchExternal);
            initializeAndProbe();
            started = true;
            disabledReason.set(null);
        } catch (AppServerException ex) {
            shutdownQuietly();
            disable(ex.reason(), ex.getMessage());
            throw ex;
        } catch (IOException ex) {
            shutdownQuietly();
            disable(AppServerDisabledReason.PROCESS_CRASHED, "failed to start app-server process");
            throw new AppServerException(
                    AppServerDisabledReason.PROCESS_CRASHED,
                    "failed to start app-server process",
                    ex);
        }
    }

    private void initializeAndProbe() {
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", AppServerProtocol.CLIENT_NAME);
        clientInfo.put("title", AppServerProtocol.CLIENT_TITLE);
        clientInfo.put("version", AppServerProtocol.CLIENT_VERSION);

        ObjectNode params = mapper.createObjectNode();
        params.set("clientInfo", clientInfo);
        // Stay on the stable API surface; experimental capabilities stay disabled by default.
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.put("experimentalApi", false);
        params.set("capabilities", capabilities);

        JsonNode initResult;
        try {
            initResult = client.request(AppServerProtocol.METHOD_INITIALIZE, params);
        } catch (AppServerException ex) {
            throw new AppServerException(
                    AppServerDisabledReason.CAPABILITY_PROBE_FAILED,
                    "initialize capability probe failed",
                    ex);
        }
        client.notify(AppServerProtocol.METHOD_INITIALIZED, mapper.createObjectNode());

        if (initResult == null || initResult.isNull()) {
            throw new AppServerException(
                    AppServerDisabledReason.CAPABILITY_PROBE_FAILED,
                    "initialize returned empty result");
        }
        // Official fields: userAgent / codexHome / platformFamily / platformOs.
        // Version pin is proven from userAgent — never from an injected observedBinaryVersion.
        String userAgent = initResult.hasNonNull("userAgent")
                ? initResult.get("userAgent").asText()
                : null;
        binaryGate.verifyVersionFromUserAgent(userAgent, config.binarySpec().expectedVersion());

        if (initResult.hasNonNull("codexHome")) {
            String remoteHome = initResult.get("codexHome").asText();
            if (remoteHome.contains("auth.json")) {
                throw new AppServerException(
                        AppServerDisabledReason.KEYRING_UNAVAILABLE,
                        "initialize advertised file credential path");
            }
        }

        // Deny path: ensure the client refuses native tools even if the server offers them.
        // Unknown/unsupported capabilities remain disabled rather than invented.
        try {
            client.request("command/exec", mapper.createObjectNode());
            throw new AppServerException(
                    AppServerDisabledReason.CAPABILITY_PROBE_FAILED,
                    "native command/exec was not denied by client allowlist");
        } catch (AppServerException ex) {
            if (ex.reason() != AppServerDisabledReason.METHOD_NOT_ALLOWLISTED) {
                throw ex;
            }
        }
    }

    static Map<String, String> sanitizedParentEnvironment(Map<String, String> parent) {
        Map<String, String> sanitized = new HashMap<>();
        if (parent == null) {
            return sanitized;
        }
        for (String name : SAFE_PARENT_ENVIRONMENT) {
            String value = parent.get(name);
            if (value != null && !value.isBlank()) {
                sanitized.put(name, value);
            }
        }
        return sanitized;
    }

    /**
     * Locate the code-mode host used by GPT-5.6 {@code code_mode_only} nested MCP calls.
     * Preference: system property, sibling of the app-server binary, then PATH lookup.
     * When the resolved path contains spaces (macOS {@code .app} bundles), copy to a
     * space-free path under {@code CODEX_HOME}/bin so fragile host spawners do not break.
     */
    static Optional<String> resolveCodeModeHostPath(Path appServerBinary) {
        return resolveCodeModeHostPath(appServerBinary, null);
    }

    static Optional<String> resolveCodeModeHostPath(Path appServerBinary, Path codexHome) {
        Optional<Path> found = findCodeModeHostBinary(appServerBinary);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Path host = found.get();
        if (codexHome != null && host.toAbsolutePath().toString().indexOf(' ') >= 0) {
            try {
                Path binDir = codexHome.toAbsolutePath().resolve("bin");
                Files.createDirectories(binDir);
                String name = host.getFileName().toString();
                Path stable = binDir.resolve(name);
                Files.copy(host, stable, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                stable.toFile().setExecutable(true, false);
                return Optional.of(stable.toAbsolutePath().toString());
            } catch (IOException ignored) {
                // Fall back to the original path; ProcessBuilder handles spaces correctly.
            }
        }
        return Optional.of(host.toAbsolutePath().toString());
    }

    private static Optional<Path> findCodeModeHostBinary(Path appServerBinary) {
        String prop = System.getProperty("chat2db.codex.codeModeHostPath");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop.trim());
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        if (appServerBinary != null) {
            Path parent = appServerBinary.toAbsolutePath().getParent();
            if (parent != null) {
                for (String name : List.of("codex-code-mode-host", "codex-code-mode-host.exe")) {
                    Path sibling = parent.resolve(name);
                    if (Files.isRegularFile(sibling) && Files.isExecutable(sibling)) {
                        return Optional.of(sibling);
                    }
                }
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                for (String name : List.of("codex-code-mode-host", "codex-code-mode-host.exe")) {
                    Path candidate = Path.of(dir.trim(), name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void shutdown() {
        shutdownQuietly();
        disable(AppServerDisabledReason.SHUTDOWN, "supervisor shutdown");
    }

    private void shutdownQuietly() {
        started = false;
        if (client != null) {
            client.close();
            client = null;
        }
        if (stderrDrain != null) {
            stderrDrain.close();
            stderrDrain = null;
        }
        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    private void disable(AppServerDisabledReason reason, String ignoredMessage) {
        disabledReason.set(reason);
        started = false;
    }

    private void ensureRunning() {
        if (!started || client == null || process == null || !process.isAlive()) {
            AppServerDisabledReason reason = disabledReason.get();
            if (reason == null) {
                reason = AppServerDisabledReason.PROCESS_NOT_RUNNING;
            }
            throw new AppServerException(reason, "app-server supervisor is not running");
        }
    }

    @Override
    public AppServerAccountView readAccount(boolean refreshToken) {
        ensureRunning();
        ObjectNode params = mapper.createObjectNode();
        params.put("refreshToken", refreshToken);
        JsonNode result = client.request(AppServerProtocol.METHOD_ACCOUNT_READ, params);
        JsonNode account = result.path("account");
        if (account.isMissingNode() || account.isNull()) {
            return new AppServerAccountView(false, null, null, null);
        }
        String type = textOrNull(account, "type");
        String email = textOrNull(account, "email");
        return new AppServerAccountView(
                type != null,
                type,
                maskEmail(email),
                textOrNull(account, "planType"));
    }

    @Override
    public AppServerLoginStartResult startChatGptLogin(String type) {
        ensureRunning();
        if (type == null || !SUPPORTED_LOGIN_TYPES.contains(type)) {
            throw new AppServerException(
                    AppServerDisabledReason.METHOD_NOT_ALLOWLISTED,
                    "only chatgpt and chatgptDeviceCode login are supported");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("type", type);
        if ("chatgpt".equals(type)) {
            // Browser flow: host success page branding; authUrl stays backend-only for JCEF open.
            params.put("useHostedLoginSuccessPage", true);
            params.put("appBrand", "chatgpt");
        }
        JsonNode result = client.request(AppServerProtocol.METHOD_ACCOUNT_LOGIN_START, params);
        // Never map apiKey fields even if a server incorrectly returns them.
        if (result.has("apiKey") || result.has("accessToken") || result.has("refreshToken")) {
            throw new AppServerException(
                    AppServerDisabledReason.PROTOCOL_MISMATCH,
                    "login result contained credential material");
        }
        return new AppServerLoginStartResult(
                textOrNull(result, "type"),
                textOrNull(result, "loginId"),
                textOrNull(result, "authUrl"),
                textOrNull(result, "verificationUrl"),
                textOrNull(result, "userCode"));
    }

    @Override
    public void cancelLogin(String loginId) {
        ensureRunning();
        ObjectNode params = mapper.createObjectNode();
        params.put("loginId", loginId);
        client.request(AppServerProtocol.METHOD_ACCOUNT_LOGIN_CANCEL, params);
    }

    @Override
    public void logout() {
        ensureRunning();
        client.request(AppServerProtocol.METHOD_ACCOUNT_LOGOUT, mapper.createObjectNode());
    }

    @Override
    public List<AppServerModelDescriptor> listModels(boolean includeHidden) {
        ensureRunning();
        List<AppServerModelDescriptor> models = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            ObjectNode params = mapper.createObjectNode();
            params.put("includeHidden", includeHidden);
            params.put("limit", 100);
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = client.request(AppServerProtocol.METHOD_MODEL_LIST, params);
            JsonNode data = result.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    List<String> modalities = new ArrayList<>();
                    List<String> reasoningEfforts = new ArrayList<>();
                    JsonNode inputModalities = item.path("inputModalities");
                    if (inputModalities.isArray()) {
                        for (JsonNode modality : inputModalities) {
                            if (modality.isTextual()) {
                                modalities.add(modality.asText());
                            }
                        }
                    }
                    JsonNode supportedReasoningEfforts = item.path("supportedReasoningEfforts");
                    if (supportedReasoningEfforts.isArray()) {
                        for (JsonNode effort : supportedReasoningEfforts) {
                            String value = effort.isTextual()
                                    ? effort.asText() : textOrNull(effort, "reasoningEffort");
                            if (value != null && !value.isBlank()) {
                                reasoningEfforts.add(value);
                            }
                        }
                    }
                    String toolMode = textOrNull(item, "toolMode");
                    if (toolMode == null) {
                        toolMode = textOrNull(item, "tool_mode");
                    }
                    models.add(new AppServerModelDescriptor(
                            textOrNull(item, "id") != null ? textOrNull(item, "id") : textOrNull(item, "model"),
                            textOrNull(item, "displayName"),
                            item.path("hidden").asBoolean(false),
                            item.path("isDefault").asBoolean(false),
                            modalities,
                            reasoningEfforts,
                            textOrNull(item, "defaultReasoningEffort"),
                            toolMode));
                }
            }
            cursor = textOrNull(result, "nextCursor");
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return List.copyOf(models);
    }

    @Override
    public AppServerThreadView startThread(String model) {
        ensureRunning();
        ObjectNode params = mapper.createObjectNode();
        if (model != null) {
            params.put("model", model);
        }
        params.put("cwd", config.workdir().toAbsolutePath().toString());
        params.put("ephemeral", true);
        params.put("approvalPolicy", "never");
        // Match config: workspace-write + network so loopback MCP tools/call is not stalled.
        params.put("sandbox", "workspace-write");
        params.put("serviceName", AppServerProtocol.CLIENT_NAME);
        params.put("developerInstructions", Chat2dbMcpToolPolicy.THREAD_DEVELOPER_INSTRUCTIONS);
        JsonNode result = client.request(AppServerProtocol.METHOD_THREAD_START, params);
        JsonNode thread = result.path("thread");
        String id = textOrNull(thread, "id");
        String sessionId = textOrNull(thread, "sessionId");
        return new AppServerThreadView(id, sessionId != null ? sessionId : id);
    }

    @Override
    public AppServerThreadView resumeThread(String threadId) {
        ensureRunning();
        ObjectNode params = mapper.createObjectNode();
        params.put("threadId", threadId);
        JsonNode result = client.request(AppServerProtocol.METHOD_THREAD_RESUME, params);
        JsonNode thread = result.path("thread");
        String id = textOrNull(thread, "id");
        String sessionId = textOrNull(thread, "sessionId");
        return new AppServerThreadView(id, sessionId != null ? sessionId : id);
    }

    @Override
    public AppServerThreadView readThread(String threadId, boolean includeTurns) {
        ensureRunning();
        ObjectNode params = mapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("includeTurns", includeTurns);
        JsonNode result = client.request(AppServerProtocol.METHOD_THREAD_READ, params);
        JsonNode thread = result.path("thread");
        String id = textOrNull(thread, "id");
        String sessionId = textOrNull(thread, "sessionId");
        return new AppServerThreadView(id, sessionId != null ? sessionId : id);
    }

    @Override
    public AppServerTurnView startTurn(String threadId, String textInput) {
        return startTurn(threadId, textInput, null);
    }

    @Override
    public AppServerTurnView startTurn(String threadId, String textInput, String reasoningEffort) {
        ensureRunning();
        ObjectNode params = mapper.createObjectNode();
        params.put("threadId", threadId);
        ArrayNode input = mapper.createArrayNode();
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        text.put("text", textInput == null ? "" : textInput);
        input.add(text);
        params.set("input", input);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            params.put("effort", reasoningEffort);
        }
        JsonNode result = client.request(AppServerProtocol.METHOD_TURN_START, params);
        JsonNode turn = result.path("turn");
        if (turn.isMissingNode() || turn.isNull()) {
            turn = result;
        }
        return new AppServerTurnView(
                textOrNull(turn, "id"),
                threadId,
                textOrNull(turn, "status"));
    }

    @Override
    public void interruptTurn(String threadId, String turnId) {
        ensureRunning();
        ObjectNode params = mapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        client.request(AppServerProtocol.METHOD_TURN_INTERRUPT, params);
    }

    @Override
    public void addEventListener(AppServerEventListener listener) {
        externalListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void removeEventListener(AppServerEventListener listener) {
        externalListeners.remove(listener);
    }

    private void dispatchExternal(String method, JsonNode redactedParams) {
        for (AppServerEventListener listener : externalListeners) {
            try {
                listener.onNotification(method, redactedParams);
            } catch (RuntimeException ignored) {
                // isolate listeners
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.substring(0, 1).toLowerCase(Locale.ROOT)
                + "***"
                + email.substring(at).toLowerCase(Locale.ROOT);
    }
}
