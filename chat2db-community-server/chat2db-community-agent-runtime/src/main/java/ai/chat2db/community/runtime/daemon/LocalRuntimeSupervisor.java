package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.runtime.provider.ExternalProviderAdapter;
import ai.chat2db.community.runtime.workspace.TaskWorkspaceManager;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LocalRuntimeSupervisor implements AutoCloseable {

    private final String daemonId;
    private final URI controlPlaneUrl;
    private final String token;
    private final Path workspaceRoot;
    private final int concurrency;
    private final Map<AgentRuntimeProviderEnum, LocalRuntimeInstallation> installations = new LinkedHashMap<>();
    private final Map<AgentRuntimeProviderEnum, AgentRuntimeDaemon> daemons = new LinkedHashMap<>();
    private final Map<AgentRuntimeProviderEnum, Thread> threads = new LinkedHashMap<>();
    private boolean started;

    public LocalRuntimeSupervisor(String daemonId, URI controlPlaneUrl, String token,
                                  Path workspaceRoot, int concurrency,
                                  List<LocalRuntimeInstallation> installations) {
        this.daemonId = daemonId;
        this.controlPlaneUrl = controlPlaneUrl;
        this.token = token;
        this.workspaceRoot = workspaceRoot;
        this.concurrency = concurrency;
        installations.forEach(installation -> this.installations.put(installation.provider(), installation));
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        installations.values().forEach(this::startInstallation);
    }

    public synchronized void refresh(List<LocalRuntimeInstallation> discovered) {
        LinkedHashMap<AgentRuntimeProviderEnum, LocalRuntimeInstallation> next = new LinkedHashMap<>();
        discovered.forEach(installation -> next.put(installation.provider(), installation));
        List<AgentRuntimeProviderEnum> stale = installations.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(next.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .toList();
        stale.forEach(this::stopInstallation);
        installations.clear();
        installations.putAll(next);
        if (started) {
            installations.values().stream()
                    .filter(installation -> !daemons.containsKey(installation.provider()))
                    .forEach(this::startInstallation);
        }
    }

    public synchronized List<LocalRuntimeInstallation> installations() {
        return List.copyOf(installations.values());
    }

    @Override
    public synchronized void close() {
        new ArrayList<>(daemons.keySet()).forEach(this::stopInstallation);
        started = false;
    }

    private void startInstallation(LocalRuntimeInstallation installation) {
        AgentRuntimeProviderEnum provider = installation.provider();
        AgentRuntimeControlPlaneClient client = new AgentRuntimeControlPlaneClient(controlPlaneUrl, token);
        ExternalProviderAdapter adapter = ExternalRuntimeProviderCatalog.createAdapter(provider);
        Path providerWorkspace = workspaceRoot.resolve(provider.name().toLowerCase(java.util.Locale.ROOT));
        TaskWorkspaceManager workspaces = new TaskWorkspaceManager(providerWorkspace);
        ExternalRuntimeClaimExecutor executor = new ExternalRuntimeClaimExecutor(
                daemonId, client, adapter, workspaces);
        AgentRuntimeDaemon daemon = new AgentRuntimeDaemon(
                daemonId, provider, installation.version(), installation.executable(), concurrency,
                client, executor, new DaemonEnvironmentResolver(installation.environment()));
        Thread thread = new Thread(daemon::run,
                "chat2db-runtime-" + provider.name().toLowerCase(java.util.Locale.ROOT));
        thread.setDaemon(false);
        daemons.put(provider, daemon);
        threads.put(provider, thread);
        thread.start();
    }

    private void stopInstallation(AgentRuntimeProviderEnum provider) {
        AgentRuntimeDaemon daemon = daemons.remove(provider);
        Thread thread = threads.remove(provider);
        if (daemon != null) {
            daemon.close();
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(10_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
