package ai.chat2db.community.start.ai.subscription.appserver;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Supervisor configuration. {@code featureEnabled} defaults to false for fail-closed rollout.
 * Binary version is proven from initialize {@code userAgent}, never from an injected observed string.
 */
public final class AppServerSupervisorConfig {

    private final boolean featureEnabled;
    private final AppServerBinarySpec binarySpec;
    private final Path codexHome;
    private final Path workdir;
    private final List<String> launchCommand;
    private final String expectedProtocolLabel;
    private final AppServerMcpEndpoint mcpEndpoint;

    public AppServerSupervisorConfig(
            boolean featureEnabled,
            AppServerBinarySpec binarySpec,
            Path codexHome,
            Path workdir,
            List<String> launchCommand,
            String expectedProtocolLabel) {
        this(featureEnabled, binarySpec, codexHome, workdir, launchCommand, expectedProtocolLabel, null);
    }

    public AppServerSupervisorConfig(
            boolean featureEnabled,
            AppServerBinarySpec binarySpec,
            Path codexHome,
            Path workdir,
            List<String> launchCommand,
            String expectedProtocolLabel,
            AppServerMcpEndpoint mcpEndpoint) {
        this.featureEnabled = featureEnabled;
        this.binarySpec = Objects.requireNonNull(binarySpec, "binarySpec");
        this.codexHome = Objects.requireNonNull(codexHome, "codexHome");
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.launchCommand = List.copyOf(Objects.requireNonNull(launchCommand, "launchCommand"));
        this.expectedProtocolLabel = Objects.requireNonNull(expectedProtocolLabel, "expectedProtocolLabel");
        this.mcpEndpoint = mcpEndpoint;
        if (launchCommand.isEmpty()) {
            throw new IllegalArgumentException("launchCommand must not be empty");
        }
    }

    public boolean featureEnabled() {
        return featureEnabled;
    }

    public AppServerBinarySpec binarySpec() {
        return binarySpec;
    }

    public Path codexHome() {
        return codexHome;
    }

    public Path workdir() {
        return workdir;
    }

    public List<String> launchCommand() {
        return launchCommand;
    }

    public String expectedProtocolLabel() {
        return expectedProtocolLabel;
    }

    public Optional<AppServerMcpEndpoint> mcpEndpoint() {
        return Optional.ofNullable(mcpEndpoint);
    }
}
