package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeHeartbeatRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeInstanceRegisterRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunClaimRequest;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AgentRuntimeDaemon implements AutoCloseable {

    private final String daemonId;
    private final AgentRuntimeProviderEnum provider;
    private final String providerVersion;
    private final Path discoveredExecutable;
    private final int maxConcurrency;
    private final AgentRuntimeControlPlaneClient controlPlane;
    private final ExternalRuntimeClaimExecutor claimExecutor;
    private final DaemonEnvironmentResolver environmentResolver;
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeat;
    private final AtomicInteger activeRuns = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile AgentRuntimeInstance instance;

    public AgentRuntimeDaemon(String daemonId, AgentRuntimeProviderEnum provider, int maxConcurrency,
                              AgentRuntimeControlPlaneClient controlPlane,
                              ExternalRuntimeClaimExecutor claimExecutor,
                              DaemonEnvironmentResolver environmentResolver) {
        this(daemonId, provider, "discovered-per-profile", null, maxConcurrency,
                controlPlane, claimExecutor, environmentResolver);
    }

    public AgentRuntimeDaemon(String daemonId, AgentRuntimeProviderEnum provider, String providerVersion,
                              Path discoveredExecutable, int maxConcurrency,
                              AgentRuntimeControlPlaneClient controlPlane,
                              ExternalRuntimeClaimExecutor claimExecutor,
                              DaemonEnvironmentResolver environmentResolver) {
        if (StringUtils.isBlank(daemonId) || provider == null || StringUtils.isBlank(providerVersion)
                || maxConcurrency <= 0 || controlPlane == null
                || claimExecutor == null || environmentResolver == null) {
            throw new IllegalArgumentException("Runtime Daemon configuration is incomplete");
        }
        this.daemonId = daemonId.trim();
        this.provider = provider;
        this.providerVersion = providerVersion.trim();
        this.discoveredExecutable = discoveredExecutable;
        this.maxConcurrency = maxConcurrency;
        this.controlPlane = controlPlane;
        this.claimExecutor = claimExecutor;
        this.environmentResolver = environmentResolver;
        this.workers = Executors.newFixedThreadPool(maxConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "chat2db-runtime-worker");
            thread.setDaemon(false);
            return thread;
        });
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat2db-runtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void run() {
        claimExecutor.recoverOrphanedProcesses();
        register();
        heartbeat.scheduleWithFixedDelay(this::heartbeatSafely, 5, 10, TimeUnit.SECONDS);
        while (!stopped.get()) {
            try {
                if (activeRuns.get() >= maxConcurrency) {
                    sleep(Duration.ofMillis(250));
                    continue;
                }
                AgentRuntimeRunClaim claim = claim();
                if (claim == null) {
                    sleep(Duration.ofSeconds(1));
                    continue;
                }
                activeRuns.incrementAndGet();
                workers.submit(() -> execute(claim));
            } catch (ControlPlaneException exception) {
                sleep(Duration.ofSeconds(2));
            }
        }
    }

    private void register() {
        AgentRuntimeInstanceRegisterRequest request = new AgentRuntimeInstanceRegisterRequest();
        request.setDaemonId(daemonId);
        request.setProvider(provider);
        request.setProviderVersion(providerVersion);
        request.setProtocolVersion(provider == AgentRuntimeProviderEnum.HERMES
                ? "acp-v1" : "codex-app-server-v2");
        request.setCapabilities(Set.of("streaming", "sessionResume", "usage", "cancellation",
                "taskWorkspace", "approvalBridge"));
        request.setMaxConcurrency(maxConcurrency);
        instance = controlPlane.register(request);
        if (instance == null || StringUtils.isBlank(instance.getId())) {
            throw new ControlPlaneException("Runtime registration returned no instance id");
        }
    }

    private AgentRuntimeRunClaim claim() {
        AgentRuntimeRunClaimRequest request = new AgentRuntimeRunClaimRequest();
        request.setDaemonId(daemonId);
        return controlPlane.claim(instance.getId(), request);
    }

    private void execute(AgentRuntimeRunClaim claim) {
        try {
            Map<String, String> environment;
            try {
                applyDiscoveredExecutable(claim);
                environment = environmentResolver.resolve(claim.getRuntimeProfile());
                claim.getRuntimeProfile().setExecutable(environmentResolver.resolveExecutable(
                        claim.getRuntimeProfile(), environment).toString());
            } catch (RuntimeException preparationFailure) {
                claimExecutor.failBeforeStart(claim, preparationFailure.getMessage());
                return;
            }
            claimExecutor.execute(claim, environment);
        } finally {
            activeRuns.decrementAndGet();
        }
    }

    private void applyDiscoveredExecutable(AgentRuntimeRunClaim claim) {
        if (discoveredExecutable == null || claim == null || claim.getRuntimeProfile() == null) {
            return;
        }
        String configured = StringUtils.trimToNull(claim.getRuntimeProfile().getExecutable());
        String defaultName = provider == AgentRuntimeProviderEnum.CODEX ? "codex" : "hermes";
        if (configured == null || defaultName.equals(configured)) {
            claim.getRuntimeProfile().setExecutable(discoveredExecutable.toString());
        }
    }

    private void heartbeatSafely() {
        if (stopped.get() || instance == null) {
            return;
        }
        try {
            AgentRuntimeHeartbeatRequest request = new AgentRuntimeHeartbeatRequest();
            request.setDaemonId(daemonId);
            request.setActiveRuns(activeRuns.get());
            request.setStatus(AgentRuntimeInstanceStatusEnum.ONLINE);
            request.setExpectedRevision(instance.getRevision());
            AgentRuntimeInstance updated = controlPlane.heartbeat(instance.getId(), request);
            if (updated != null) {
                instance = updated;
            }
        } catch (RuntimeException ignored) {
            // Claim/lease calls remain authoritative; the next heartbeat retries.
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stopped.set(true);
        }
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        heartbeat.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
