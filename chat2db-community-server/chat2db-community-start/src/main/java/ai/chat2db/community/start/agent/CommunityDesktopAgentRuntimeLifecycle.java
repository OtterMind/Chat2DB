package ai.chat2db.community.start.agent;

import ai.chat2db.community.runtime.daemon.ExternalRuntimeProviderCatalog;
import ai.chat2db.community.runtime.daemon.LocalRuntimeDiscovery;
import ai.chat2db.community.runtime.daemon.LocalRuntimeInstallation;
import ai.chat2db.community.runtime.daemon.LocalRuntimeSupervisor;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.web.api.util.AgentRuntimeDaemonUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class CommunityDesktopAgentRuntimeLifecycle {

    private static final String ENABLED_PROPERTY = "chat2db.agent.runtime.auto-discovery";

    private final Environment environment;
    private LocalRuntimeSupervisor supervisor;

    public CommunityDesktopAgentRuntimeLifecycle(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!ConfigUtils.isCommunity() || !ConfigUtils.isDesktop()
                || !environment.getProperty(ENABLED_PROPERTY, Boolean.class, true)
                || supervisor != null) {
            return;
        }
        String token = AgentRuntimeDaemonUtils.runtimeToken();
        if (StringUtils.isBlank(token)) {
            log.warn("Local Agent Runtime discovery skipped because the daemon token is unavailable");
            return;
        }
        List<LocalRuntimeInstallation> installations = new LocalRuntimeDiscovery().discover(
                Set.copyOf(ExternalRuntimeProviderCatalog.providers()));
        if (installations.isEmpty()) {
            log.info("No supported local Agent Runtime was detected");
            return;
        }
        int port = environment.getProperty("server.port", Integer.class, 10825);
        int concurrency = environment.getProperty("chat2db.agent.runtime.local-concurrency", Integer.class, 1);
        if (concurrency <= 0) {
            log.warn("Local Agent Runtime discovery skipped because configured concurrency is not positive");
            return;
        }
        URI controlPlane = URI.create("http://127.0.0.1:" + port + "/");
        Path workspace = Path.of(ConfigUtils.getBasePath(), "agent-runtime").toAbsolutePath().normalize();
        String daemonId = "community-desktop-" + ConfigUtils.getClientId();
        supervisor = new LocalRuntimeSupervisor(
                daemonId, controlPlane, token, workspace, concurrency, installations);
        supervisor.start();
        log.info("Started local Agent Runtimes: {}", installations.stream()
                .map(installation -> installation.provider().name() + " " + installation.version())
                .toList());
    }

    @PreDestroy
    public synchronized void close() {
        if (supervisor != null) {
            supervisor.close();
            supervisor = null;
        }
    }
}
