package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentRuntimeAdapter;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

@Configuration
@Conditional(DesktopAgentRuntimeCondition.class)
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
    public PiRuntimeInstallation piRuntimeInstallation(
            PiRuntimePaths paths,
            @Value("${chat2db.agent.pi.version:0.85.1}") String version,
            @Value("${chat2db.agent.pi.source:}") String source) {
        if (source == null || source.isBlank()) {
            return environment -> {
                throw new IOException("Pi runtime download source is not configured");
            };
        }
        return new PiRuntimeInstaller(paths, version, URI.create(source));
    }

    @Bean
    public PiAgentRuntimeFeatureService piAgentRuntimeFeatureService(
            AgentFeatureFlagStorage flagStorage,
            PiRuntimeEnvironmentChecker environmentChecker,
            PiRuntimeInstallation installer) {
        return new PiAgentRuntimeFeatureService(flagStorage, environmentChecker, installer);
    }

    @Bean(destroyMethod = "close")
    public PiProcessSupervisor piProcessSupervisor(
            PiRuntimeLayout layout,
            @Value("${chat2db.agent.pi.max-processes:3}") int maximumProcesses) {
        Path sessionDataRoot = Path.of(ConfigUtils.getEnvBasePath())
                .resolve("storage/ai-chat-history-v2/runtime/pi");
        return new PiProcessSupervisor(layout, sessionDataRoot, maximumProcesses);
    }

    @Bean
    public PiRuntimeSessionLauncher piRuntimeSessionLauncher(PiProcessSupervisor supervisor) {
        return new PiRuntimeSessionLauncher(supervisor, List.of());
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
