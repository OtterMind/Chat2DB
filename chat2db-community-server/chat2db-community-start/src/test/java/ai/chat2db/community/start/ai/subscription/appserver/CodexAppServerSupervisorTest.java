package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerAccountView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerLoginStartResult;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;
import ai.chat2db.community.start.ai.subscription.appserver.internal.AppServerHomeLayout;
import ai.chat2db.community.start.ai.subscription.appserver.internal.BinaryIntegrityGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CodexAppServerSupervisorTest {

    @TempDir
    Path tempDir;

    private CodexAppServerSupervisor supervisor;

    @AfterEach
    void tearDown() {
        if (supervisor != null) {
            supervisor.shutdown();
        }
    }

    @Test
    void remainsDisabledByDefault() throws Exception {
        AppServerSupervisorConfig config = config(false, true);
        supervisor = new CodexAppServerSupervisor(config, () -> true, (command, workdir, environment) -> {
            throw new AssertionError("must not launch when disabled");
        }, new ObjectMapper());

        assertFalse(supervisor.isEnabled());
        assertEquals(AppServerDisabledReason.FEATURE_DISABLED_BY_DEFAULT,
                supervisor.disabledReason().orElseThrow());
        AppServerException ex = assertThrows(AppServerException.class, supervisor::start);
        assertEquals(AppServerDisabledReason.FEATURE_DISABLED_BY_DEFAULT, ex.reason());
    }

    @Test
    void failsClosedWhenKeyringUnavailable() throws Exception {
        AppServerSupervisorConfig config = config(true, true);
        supervisor = new CodexAppServerSupervisor(config, () -> false, (command, workdir, environment) -> {
            throw new AssertionError("must not launch without keyring");
        }, new ObjectMapper());

        AppServerException ex = assertThrows(AppServerException.class, supervisor::start);
        assertEquals(AppServerDisabledReason.KEYRING_UNAVAILABLE, ex.reason());
        assertFalse(supervisor.isEnabled());
    }

    @Test
    void failsClosedWhenBinaryChecksumMismatches() throws Exception {
        AppServerSupervisorConfig config = config(true, false);
        supervisor = new CodexAppServerSupervisor(config, () -> true);
        AppServerException ex = assertThrows(AppServerException.class, supervisor::start);
        assertEquals(AppServerDisabledReason.BINARY_CHECKSUM_MISMATCH, ex.reason());
    }

    @Test
    void rejectsFileCredentialFallbackDuringHomePrepare() throws Exception {
        Path home = tempDir.resolve("codex-home-auth");
        Files.createDirectories(home);
        Files.writeString(home.resolve("auth.json"), "{\"tokens\":\"secret\"}");
        AppServerHomeLayout layout = new AppServerHomeLayout(home, tempDir.resolve("work"));
        AppServerException ex = assertThrows(AppServerException.class, layout::prepare);
        assertEquals(AppServerDisabledReason.KEYRING_UNAVAILABLE, ex.reason());
    }

    @Test
    void startsAgainstFakeProcessAndCoversAllowlistedOperations() throws Exception {
        Path binary = tempDir.resolve("pinned-app-server");
        Files.writeString(binary, "pinned-bytes", StandardCharsets.UTF_8);
        String sha = BinaryIntegrityGate.sha256Hex(binary);
        Path home = tempDir.resolve("codex-home");
        Path work = tempDir.resolve("workdir");

        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "0.145.0", sha, "chat2db-pinned-v1");
        AppServerSupervisorConfig config = new AppServerSupervisorConfig(
                true,
                spec,
                home,
                work,
                List.of(binary.toString(), "app-server"),
                "chat2db-pinned-v1");

        AtomicReference<PipeManagedProcess> processRef = new AtomicReference<>();
        supervisor = new CodexAppServerSupervisor(
                config,
                () -> true,
                (command, workdir, environment) -> {
                    assertEquals(List.of(binary.toString(), "app-server"), command);
                    assertEquals(work.toAbsolutePath(), workdir);
                    assertEquals(home.toAbsolutePath().toString(), environment.get("CODEX_HOME"));
                    assertEquals(System.getenv("HOME"), environment.get("HOME"));
                    assertNotEquals(home.toAbsolutePath().toString(), environment.get("HOME"));
                    assertFalse(environment.containsKey("OPENAI_API_KEY"));
                    PipeManagedProcess managed = new PipeManagedProcess();
                    // Version proven from initialize userAgent, not injection.
                    managed.fake().setUserAgent("codex_app_server/0.145.0");
                    processRef.set(managed);
                    return managed;
                },
                new ObjectMapper());

        supervisor.start();
        assertTrue(supervisor.isEnabled());
        assertTrue(Files.exists(home.resolve("config.toml")));
        String toml = Files.readString(home.resolve("config.toml"));
        assertTrue(toml.contains("cli_auth_credentials_store = \"keyring\""));
        assertTrue(toml.contains("shell_tool = false"));
        assertTrue(toml.contains("unified_exec = false"));
        assertTrue(toml.contains("plugins = false"));
        assertTrue(toml.contains("in_app_browser = false"));
        assertTrue(toml.contains("browser_use = false"));
        assertTrue(toml.contains("browser_use_full_cdp_access = false"));
        assertTrue(toml.contains("browser_use_external = false"));
        assertTrue(toml.contains("computer_use = false"));
        assertTrue(toml.contains("plugin_sharing = false"));
        assertTrue(toml.contains("image_generation = false"));
        assertTrue(toml.contains("skill_mcp_dependency_install = false"));
        assertFalse(toml.contains("mcp_servers"));
        assertFalse(toml.matches("(?s).*\\b(access_token|refresh_token|api_key|OPENAI_API_KEY)\\s*=.*"));
        assertFalse(toml.contains("sk-"));
        assertFalse(toml.contains("Bearer "));

        AppServerAccountView account = supervisor.readAccount(false);
        assertTrue(account.authenticated());
        assertEquals("chatgpt", account.accountType());
        assertEquals("u***@example.com", account.maskedEmail());
        assertEquals("plus", account.planType());

        AppServerLoginStartResult login = supervisor.startChatGptLogin("chatgpt");
        assertEquals("login-1", login.loginId());
        assertNotNull(login.authUrl());

        assertThrows(AppServerException.class, () -> supervisor.startChatGptLogin("apiKey"));

        List<AppServerModelDescriptor> models = supervisor.listModels(false);
        assertEquals(1, models.size());
        assertEquals("gpt-5.4", models.get(0).id());
        assertEquals(List.of("low", "high", "xhigh"), models.get(0).supportedReasoningEfforts());
        assertEquals("medium", models.get(0).defaultReasoningEffort());

        AppServerThreadView thread = supervisor.startThread("gpt-5.4");
        assertEquals("thr_1", thread.threadId());
        assertEquals("workspace-write", processRef.get().fake().lastThreadStartParams().path("sandbox").asText());
        String developerInstructions = processRef.get().fake().lastThreadStartParams()
                .path("developerInstructions").asText();
        assertTrue(developerInstructions.contains("chat2db_subscription"));
        assertTrue(developerInstructions.contains("list_mcp_resources"));
        assertTrue(developerInstructions.contains("list_all_datasources"));
        assertTrue(developerInstructions.toLowerCase(java.util.Locale.ROOT).contains("never create"));
        // Direct MCP only; nested exec/code_mode wrappers are prohibited.
        assertTrue(developerInstructions.toLowerCase(java.util.Locale.ROOT).contains("direct"));
        assertTrue(developerInstructions.toLowerCase(java.util.Locale.ROOT).contains("do not use exec")
                || developerInstructions.contains("Do not use exec"));
        assertTrue(developerInstructions.toLowerCase(java.util.Locale.ROOT).contains("connection ui")
                || developerInstructions.contains("connection UI"));
        AppServerTurnView turn = supervisor.startTurn(thread.threadId(), "hello", "high");
        assertEquals("turn_1", turn.turnId());
        assertEquals("high", processRef.get().fake().lastTurnStartParams().path("effort").asText());
        supervisor.interruptTurn(thread.threadId(), turn.turnId());
        supervisor.logout();

        supervisor.shutdown();
        assertFalse(supervisor.isEnabled());
        assertEquals(AppServerDisabledReason.SHUTDOWN, supervisor.disabledReason().orElseThrow());
        assertFalse(processRef.get().isAlive());
    }

    @Test
    void failsClosedWhenUserAgentVersionMismatchesPin() throws Exception {
        Path binary = tempDir.resolve("pinned-version");
        Files.writeString(binary, "pinned-bytes", StandardCharsets.UTF_8);
        String sha = BinaryIntegrityGate.sha256Hex(binary);
        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "0.145.0", sha, "chat2db-pinned-v1");
        AppServerSupervisorConfig config = new AppServerSupervisorConfig(
                true,
                spec,
                tempDir.resolve("home-ver"),
                tempDir.resolve("work-ver"),
                List.of(binary.toString()),
                "chat2db-pinned-v1");

        supervisor = new CodexAppServerSupervisor(
                config,
                () -> true,
                (command, workdir, environment) -> {
                    PipeManagedProcess managed = new PipeManagedProcess();
                    managed.fake().setUserAgent("codex_app_server/9.9.9");
                    return managed;
                },
                new ObjectMapper());

        AppServerException ex = assertThrows(AppServerException.class, supervisor::start);
        assertEquals(AppServerDisabledReason.BINARY_VERSION_MISMATCH, ex.reason());
        assertFalse(supervisor.isEnabled());
    }

    @Test
    void failsClosedWhenUserAgentMissingVersion() throws Exception {
        Path binary = tempDir.resolve("pinned-missing-ver");
        Files.writeString(binary, "pinned-bytes", StandardCharsets.UTF_8);
        String sha = BinaryIntegrityGate.sha256Hex(binary);
        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "0.145.0", sha, "chat2db-pinned-v1");
        AppServerSupervisorConfig config = new AppServerSupervisorConfig(
                true,
                spec,
                tempDir.resolve("home-miss"),
                tempDir.resolve("work-miss"),
                List.of(binary.toString()),
                "chat2db-pinned-v1");

        supervisor = new CodexAppServerSupervisor(
                config,
                () -> true,
                (command, workdir, environment) -> {
                    PipeManagedProcess managed = new PipeManagedProcess();
                    managed.fake().setUserAgent("codex without semver");
                    return managed;
                },
                new ObjectMapper());

        AppServerException ex = assertThrows(AppServerException.class, supervisor::start);
        assertEquals(AppServerDisabledReason.BINARY_VERSION_MISMATCH, ex.reason());
    }

    @Test
    void injectsMcpCapabilityIntoProcessEnvAndNotConfigToml() throws Exception {
        Path binary = tempDir.resolve("pinned-mcp");
        Files.writeString(binary, "pinned-bytes", StandardCharsets.UTF_8);
        String sha = BinaryIntegrityGate.sha256Hex(binary);
        Path home = tempDir.resolve("home-mcp");
        Path work = tempDir.resolve("work-mcp");
        String capability = "b".repeat(64);
        AppServerMcpEndpoint mcp = new AppServerMcpEndpoint(
                "http://127.0.0.1:45678/mcp",
                "CHAT2DB_MCP_CAPABILITY",
                capability);
        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "0.145.0", sha, "chat2db-pinned-v1");
        AppServerSupervisorConfig config = new AppServerSupervisorConfig(
                true,
                spec,
                home,
                work,
                List.of(binary.toString(), "app-server"),
                "chat2db-pinned-v1",
                mcp);

        AtomicReference<Map<String, String>> envRef = new AtomicReference<>();
        supervisor = new CodexAppServerSupervisor(
                config,
                () -> true,
                (command, workdir, environment) -> {
                    envRef.set(Map.copyOf(environment));
                    PipeManagedProcess managed = new PipeManagedProcess();
                    managed.fake().setUserAgent("codex_app_server/0.145.0");
                    return managed;
                },
                new ObjectMapper());

        supervisor.start();
        assertTrue(supervisor.isEnabled());

        String toml = Files.readString(home.resolve("config.toml"));
        assertTrue(toml.contains("http://127.0.0.1:45678/mcp"));
        assertTrue(toml.contains("127.0.0.1"));
        assertTrue(toml.contains("CHAT2DB_MCP_CAPABILITY"));
        assertFalse(toml.contains(capability), "config.toml must not contain capability value");

        Map<String, String> env = envRef.get();
        assertNotNull(env);
        assertEquals(capability, env.get("CHAT2DB_MCP_CAPABILITY"));
        assertFalse(env.containsKey("OPENAI_API_KEY"));
    }

    @Test
    void childEnvironmentKeepsKeyringAndNetworkPlumbingButDropsProviderCredentials() {
        Map<String, String> sanitized = CodexAppServerSupervisor.sanitizedParentEnvironment(Map.of(
                "PATH", "/usr/bin",
                "HOME", "/Users/tester",
                "DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus",
                "XDG_RUNTIME_DIR", "/run/user/1000",
                "SystemRoot", "C:\\Windows",
                "HTTPS_PROXY", "http://127.0.0.1:7890",
                "OPENAI_API_KEY", "must-not-pass",
                "CODEX_API_KEY", "must-not-pass",
                "CHATGPT_ACCESS_TOKEN", "must-not-pass"));

        assertEquals("/Users/tester", sanitized.get("HOME"));
        assertEquals("unix:path=/run/user/1000/bus", sanitized.get("DBUS_SESSION_BUS_ADDRESS"));
        assertEquals("/run/user/1000", sanitized.get("XDG_RUNTIME_DIR"));
        assertEquals("C:\\Windows", sanitized.get("SystemRoot"));
        assertEquals("http://127.0.0.1:7890", sanitized.get("HTTPS_PROXY"));
        assertFalse(sanitized.containsKey("OPENAI_API_KEY"));
        assertFalse(sanitized.containsKey("CODEX_API_KEY"));
        assertFalse(sanitized.containsKey("CHATGPT_ACCESS_TOKEN"));
    }

    @Test
    void failsClosedWhenReleaseProtocolPinMismatchesBinaryManifest() throws Exception {
        Path binary = tempDir.resolve("pinned-app-server");
        Files.writeString(binary, "pinned-bytes", StandardCharsets.UTF_8);
        String sha = BinaryIntegrityGate.sha256Hex(binary);
        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "0.145.0", sha, "manifest-pin-a");
        AppServerSupervisorConfig config = new AppServerSupervisorConfig(
                true,
                spec,
                tempDir.resolve("home2"),
                tempDir.resolve("work2"),
                List.of(binary.toString()),
                "manifest-pin-b");

        supervisor = new CodexAppServerSupervisor(
                config,
                () -> true,
                (command, workdir, environment) -> {
                    throw new AssertionError("must not launch when release pins disagree");
                },
                new ObjectMapper());

        AppServerException ex = assertThrows(AppServerException.class, supervisor::start);
        assertEquals(AppServerDisabledReason.PROTOCOL_MISMATCH, ex.reason());
        assertFalse(supervisor.isEnabled());
    }

    private AppServerSupervisorConfig config(boolean enabled, boolean correctChecksum) throws Exception {
        Path binary = tempDir.resolve("bin-" + enabled + "-" + correctChecksum);
        Files.writeString(binary, "content-" + correctChecksum, StandardCharsets.UTF_8);
        String sha = correctChecksum
                ? BinaryIntegrityGate.sha256Hex(binary)
                : "b".repeat(64);
        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "1.0.0", sha, "chat2db-pinned-v1");
        return new AppServerSupervisorConfig(
                enabled,
                spec,
                tempDir.resolve("home-" + enabled + "-" + correctChecksum),
                tempDir.resolve("work-" + enabled + "-" + correctChecksum),
                List.of(binary.toAbsolutePath().toString(), "app-server"),
                "chat2db-pinned-v1");
    }
}
