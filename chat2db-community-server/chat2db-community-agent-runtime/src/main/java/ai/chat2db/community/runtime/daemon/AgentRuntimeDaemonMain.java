package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public final class AgentRuntimeDaemonMain {

    private static final String URL_ENV = "CHAT2DB_AGENT_RUNTIME_URL";
    private static final String TOKEN_ENV = "CHAT2DB_AGENT_RUNTIME_TOKEN";
    private static final String DAEMON_ID_ENV = "CHAT2DB_AGENT_RUNTIME_DAEMON_ID";
    private static final String WORKSPACE_ENV = "CHAT2DB_AGENT_RUNTIME_WORKSPACE";
    private static final String CONCURRENCY_ENV = "CHAT2DB_AGENT_RUNTIME_CONCURRENCY";
    private static final String PROVIDER_ENV = "CHAT2DB_AGENT_RUNTIME_PROVIDER";

    private AgentRuntimeDaemonMain() {
    }

    public static void main(String[] args) {
        URI controlPlaneUrl = URI.create(value(URL_ENV, "http://127.0.0.1:10825/"));
        String token = required(TOKEN_ENV);
        String providerValue = value(PROVIDER_ENV, "AUTO").toUpperCase(java.util.Locale.ROOT);
        Set<AgentRuntimeProviderEnum> providers = "AUTO".equals(providerValue)
                ? Set.copyOf(ExternalRuntimeProviderCatalog.providers())
                : Set.of(externalProvider(providerValue));
        String daemonId = value(DAEMON_ID_ENV,
                "community-local-daemon");
        Path workspaceRoot = Path.of(value(WORKSPACE_ENV,
                Path.of(System.getProperty("java.io.tmpdir"), "chat2db-agent-runtime").toString()))
                .toAbsolutePath().normalize();
        int concurrency = Integer.parseInt(value(CONCURRENCY_ENV, "1"));

        List<LocalRuntimeInstallation> installations = new LocalRuntimeDiscovery().discover(providers);
        if (installations.isEmpty()) {
            throw new IllegalStateException("No supported external Agent Runtime was detected on this machine");
        }
        LocalRuntimeSupervisor supervisor = new LocalRuntimeSupervisor(
                daemonId, controlPlaneUrl, token, workspaceRoot, concurrency, installations);
        Runtime.getRuntime().addShutdownHook(new Thread(supervisor::close, "chat2db-runtime-shutdown"));
        supervisor.start();
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            supervisor.close();
        }
    }

    private static AgentRuntimeProviderEnum externalProvider(String value) {
        AgentRuntimeProviderEnum provider = AgentRuntimeProviderEnum.valueOf(value);
        if (!ExternalRuntimeProviderCatalog.providers().contains(provider)) {
            throw new IllegalArgumentException("Runtime Daemon supports AUTO, CODEX, HERMES, or DSH providers");
        }
        return provider;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value.trim();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
