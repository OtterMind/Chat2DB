package ai.chat2db.community.start.config.agent;

import ai.chat2db.community.agent.impl.pi.AgentRuntimeAdapterImpl;
import ai.chat2db.community.agent.impl.pi.AgentRuntimeEnvironmentCheckerImpl;
import ai.chat2db.community.agent.impl.pi.AgentRuntimeInstallationImpl;
import ai.chat2db.community.agent.impl.pi.PiProcessSupervisor;
import ai.chat2db.community.agent.impl.pi.PiRuntimeArchiveTrustImpl;
import ai.chat2db.community.agent.impl.pi.PiRuntimeLayout;
import ai.chat2db.community.agent.impl.pi.PiRuntimePaths;
import ai.chat2db.community.agent.impl.pi.PiSessionLauncherImpl;
import ai.chat2db.community.domain.api.model.task.agent.PiRuntimeInstallTaskSpec;
import ai.chat2db.community.domain.api.service.agent.IAgentFeatureFlagStorage;
import ai.chat2db.community.domain.api.service.agent.IAgentWorkspaceStorage;
import ai.chat2db.community.domain.api.service.agent.IAiAgentRuntimeFeatureService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentWorkspaceService;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.domain.core.impl.agent.AiAgentRuntimeFeatureServiceImpl;
import ai.chat2db.community.domain.core.impl.agent.AiAgentWorkspaceServiceImpl;
import ai.chat2db.community.domain.core.impl.task.pi.TaskExecutorImpl;
import ai.chat2db.community.jcef.desktop.NativeWorkspaceDirectoryChooser;
import ai.chat2db.community.storage.agent.AgentFeatureFlagStorageImpl;
import ai.chat2db.community.storage.agent.AgentWorkspaceStorageImpl;
import ai.chat2db.community.tools.agent.runtime.IAgentModelAccessProvider;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeAdapter;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEnvironmentChecker;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeInstallation;
import ai.chat2db.community.tools.agent.runtime.IAgentToolAccessProvider;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.util.ConfigUtils;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@Conditional(LocalAgentRuntimeCondition.class)
public class PiAgentRuntimeConfiguration {

    @Bean
    public PiRuntimePaths piRuntimePaths() {
        return new PiRuntimePaths();
    }

    @Bean
    public PiRuntimeLayout piRuntimeLayout(
            PiRuntimePaths paths,
            @Value("${chat2db.agent.pi.version:0.85.1}") String version) {
        return new PiRuntimeLayout(paths.installations(), version);
    }

    @Bean
    public AgentRuntimeEnvironmentCheckerImpl piRuntimeEnvironmentChecker(PiRuntimeLayout layout) {
        return new AgentRuntimeEnvironmentCheckerImpl(layout);
    }

    @Bean
    public IAgentFeatureFlagStorage agentFeatureFlagStorage() {
        return new AgentFeatureFlagStorageImpl();
    }

    @Bean
    public IAgentRuntimeInstallation piRuntimeInstallation(
            PiRuntimePaths paths,
            @Value("${chat2db.agent.pi.version:0.85.1}") String version,
            @Value("${chat2db.agent.pi.source:}") String source,
            Environment springEnvironment) {
        if (source == null || source.isBlank()) {
            return environment -> {
                throw new IOException("Pi runtime download source is not configured");
            };
        }
        return new AgentRuntimeInstallationImpl(
                paths,
                version,
                URI.create(source),
                new PiRuntimeArchiveTrustImpl(platform -> springEnvironment.getProperty(
                        "chat2db.agent.pi.archive-sha256." + platform)));
    }

    @Bean
    public IAiAgentRuntimeFeatureService piAgentRuntimeFeatureService(
            IAgentFeatureFlagStorage flagStorage,
            IAgentRuntimeEnvironmentChecker environmentChecker,
            IAgentRuntimeInstallation installer,
            TaskService taskService) {
        return new AiAgentRuntimeFeatureServiceImpl(flagStorage, environmentChecker, installer, taskService);
    }

    @Bean
    public TaskExecutor<PiRuntimeInstallTaskSpec> piRuntimeInstallTaskExecutor(IAgentRuntimeInstallation installation) {
        return new TaskExecutorImpl(installation);
    }

    @Bean(destroyMethod = "close")
    public PiProcessSupervisor piProcessSupervisor(
            PiRuntimeLayout layout,
            AgentRuntimeEnvironmentCheckerImpl environmentChecker,
            @Value("${chat2db.version}") String applicationVersion,
            @Value("${chat2db.agent.pi.max-processes:3}") int maximumProcesses) {
        Path sessionDataRoot = Path.of(ConfigUtils.getEnvBasePath())
                .resolve("storage/ai-chat-history-v2/runtime/pi");
        return new PiProcessSupervisor(layout, sessionDataRoot, maximumProcesses, () -> {
            var report = environmentChecker.inspect(
                    new ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest(
                            applicationVersion,
                            System.getProperty("os.name", "unknown"),
                            System.getProperty("os.arch", "unknown")));
            if (!report.isUsable()) {
                throw new IOException("Pi runtime failed its launch preflight: "
                        + report.diagnostics().getOrDefault("reason", "unknown reason"));
            }
        });
    }

    @Bean
    public PiSessionLauncherImpl piRuntimeSessionLauncher(
            PiProcessSupervisor supervisor,
            IAgentModelAccessProvider modelAccessService,
            IAgentToolAccessProvider toolAccessService) {
        return new PiSessionLauncherImpl(supervisor, List.of(), modelAccessService, toolAccessService);
    }

    @Bean
    public IAgentWorkspaceStorage agentWorkspaceStorage() {
        return new AgentWorkspaceStorageImpl();
    }

    @Bean
    public IAiAgentWorkspaceService agentWorkspaceService(IAgentWorkspaceStorage storage) {
        return new AiAgentWorkspaceServiceImpl(storage,
                Path.of(ConfigUtils.getEnvBasePath()).resolve("storage/ai-chat-history-v2/workspaces"),
                NativeWorkspaceDirectoryChooser::choose);
    }

    @Bean
    public IAgentRuntimeAdapter piAgentRuntimeAdapter(
            PiRuntimeLayout layout,
            AgentRuntimeEnvironmentCheckerImpl environmentChecker,
            PiSessionLauncherImpl sessionLauncher,
            IAgentFeatureFlagStorage flagStorage) {
        return new AgentRuntimeAdapterImpl(
                layout.version(), "rpc-v1", environmentChecker, sessionLauncher,
                () -> flagStorage.isEnabled(AgentRuntimeType.PI));
    }
}
