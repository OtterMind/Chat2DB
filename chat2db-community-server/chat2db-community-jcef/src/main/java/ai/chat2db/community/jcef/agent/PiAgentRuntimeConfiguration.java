package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentRuntimeAdapter;
import ai.chat2db.community.domain.api.service.agent.AgentModelAccessService;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import ai.chat2db.community.domain.api.service.task.TaskService;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

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
    public PiRuntimeEnvironmentChecker piRuntimeEnvironmentChecker(PiRuntimeLayout layout) {
        return new PiRuntimeEnvironmentChecker(layout);
    }

    @Bean
    public AgentFeatureFlagStorage agentFeatureFlagStorage() {
        return new SettingsAgentFeatureFlagStorage();
    }

    @Bean
    public BashEnvironmentChecker bashEnvironmentChecker() {
        return new BashEnvironmentChecker();
    }

    @Bean
    public BashAgentFeatureService bashAgentFeatureService(
            AgentFeatureFlagStorage flagStorage,
            BashEnvironmentChecker environmentChecker) {
        return new BashAgentFeatureService(flagStorage, environmentChecker);
    }

    @Bean
    public PiRuntimeInstallation piRuntimeInstallation(
            PiRuntimePaths paths,
            @Value("${chat2db.agent.pi.version:0.85.1}") String version,
            @Value("${chat2db.agent.pi.source:}") String source,
            Environment springEnvironment) {
        if (source == null || source.isBlank()) {
            return environment -> {
                throw new IOException("Pi runtime download source is not configured");
            };
        }
        return new PiRuntimeInstaller(
                paths,
                version,
                URI.create(source),
                new PinnedPiRuntimeArchiveTrust(platform -> springEnvironment.getProperty(
                        "chat2db.agent.pi.archive-sha256." + platform)));
    }

    @Bean
    public PiAgentRuntimeFeatureService piAgentRuntimeFeatureService(
            AgentFeatureFlagStorage flagStorage,
            PiRuntimeEnvironmentChecker environmentChecker,
            PiRuntimeInstallation installer,
            TaskService taskService) {
        return new PiAgentRuntimeFeatureService(flagStorage, environmentChecker, installer, taskService);
    }

    @Bean(destroyMethod = "close")
    public PiProcessSupervisor piProcessSupervisor(
            PiRuntimeLayout layout,
            PiRuntimeEnvironmentChecker environmentChecker,
            @Value("${chat2db.version}") String applicationVersion,
            @Value("${chat2db.agent.pi.max-processes:3}") int maximumProcesses) {
        Path sessionDataRoot = Path.of(ConfigUtils.getEnvBasePath())
                .resolve("storage/ai-chat-history-v2/runtime/pi");
        return new PiProcessSupervisor(layout, sessionDataRoot, maximumProcesses, () -> {
            var report = environmentChecker.inspect(
                    new ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest(
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
    public PiRuntimeSessionLauncher piRuntimeSessionLauncher(
            PiProcessSupervisor supervisor,
            AgentModelAccessService modelAccessService,
            ai.chat2db.community.domain.api.service.agent.AgentToolAccessService toolAccessService) {
        return new PiRuntimeSessionLauncher(supervisor, List.of(), modelAccessService, toolAccessService);
    }

    @Bean
    public ai.chat2db.community.domain.api.service.agent.AgentShellSettingsService agentShellSettingsService() {
        return new BashSettingsService(new SettingsAgentShellSettingsStorage(),
                Path.of(ConfigUtils.getEnvBasePath()).resolve("storage/ai-chat-history-v2/workspaces"));
    }

    @Bean
    public ai.chat2db.community.domain.api.service.agent.AgentShellExecutor agentShellExecutor(
            ai.chat2db.community.domain.api.service.agent.AgentShellSettingsService settings) {
        return new BashSandboxExecutor(settings);
    }

    @Bean
    public AgentRuntimeAdapter piAgentRuntimeAdapter(
            PiRuntimeLayout layout,
            PiRuntimeEnvironmentChecker environmentChecker,
            PiRuntimeSessionLauncher sessionLauncher,
            PiAgentRuntimeFeatureService featureService) {
        return new PiAgentRuntimeAdapter(
                layout.version(), "rpc-v1", environmentChecker, sessionLauncher, featureService::isEnabled);
    }
}
