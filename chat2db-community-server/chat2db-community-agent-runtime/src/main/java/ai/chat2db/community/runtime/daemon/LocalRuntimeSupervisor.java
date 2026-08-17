package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.runtime.provider.ExternalProviderAdapter;
import ai.chat2db.community.runtime.workspace.TaskWorkspaceManager;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalRuntimeSupervisor implements AutoCloseable {

    private final String daemonId;
    private final URI controlPlaneUrl;
    private final String token;
    private final Path workspaceRoot;
    private final int concurrency;
    private final List<LocalRuntimeInstallation> installations;
    private final List<AgentRuntimeDaemon> daemons = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();

    public LocalRuntimeSupervisor(String daemonId, URI controlPlaneUrl, String token,
                                  Path workspaceRoot, int concurrency,
                                  List<LocalRuntimeInstallation> installations) {
        this.daemonId = daemonId;
        this.controlPlaneUrl = controlPlaneUrl;
        this.token = token;
        this.workspaceRoot = workspaceRoot;
        this.concurrency = concurrency;
        this.installations = List.copyOf(installations);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (LocalRuntimeInstallation installation : installations) {
            AgentRuntimeProviderEnum provider = installation.provider();
            AgentRuntimeControlPlaneClient client = new AgentRuntimeControlPlaneClient(controlPlaneUrl, token);
            ExternalProviderAdapter adapter = ExternalRuntimeProviderCatalog.createAdapter(provider);
            Path providerWorkspace = workspaceRoot.resolve(provider.name().toLowerCase(java.util.Locale.ROOT));
            TaskWorkspaceManager workspaces = new TaskWorkspaceManager(providerWorkspace);
            ExternalRuntimeClaimExecutor executor = new ExternalRuntimeClaimExecutor(
                    daemonId, client, adapter, workspaces);
            AgentRuntimeDaemon daemon = new AgentRuntimeDaemon(
                    daemonId, provider, installation.version(), installation.executable(), concurrency,
                    client, executor, new DaemonEnvironmentResolver());
            Thread thread = new Thread(daemon::run,
                    "chat2db-runtime-" + provider.name().toLowerCase(java.util.Locale.ROOT));
            thread.setDaemon(false);
            daemons.add(daemon);
            threads.add(thread);
            thread.start();
        }
    }

    public List<LocalRuntimeInstallation> installations() {
        return installations;
    }

    @Override
    public void close() {
        for (AgentRuntimeDaemon daemon : daemons) {
            daemon.close();
        }
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(10_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
