package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeFeatureState;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeFeatureService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class PiAgentRuntimeFeatureService implements AgentRuntimeFeatureService {

    private final AgentFeatureFlagStorage flagStorage;
    private final PiRuntimeEnvironmentChecker environmentChecker;
    private final PiRuntimeInstallation installer;

    public PiAgentRuntimeFeatureService(
            AgentFeatureFlagStorage flagStorage,
            PiRuntimeEnvironmentChecker environmentChecker,
            PiRuntimeInstallation installer) {
        this.flagStorage = flagStorage;
        this.environmentChecker = environmentChecker;
        this.installer = installer;
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.PI;
    }

    @Override
    public AgentRuntimeFeatureState check(AgentRuntimeEnvironmentRequest environment) {
        AgentRuntimeEnvironmentReport report = environmentChecker.inspect(environment);
        return new AgentRuntimeFeatureState(
                runtimeType(), flagStorage.isEnabled(runtimeType()), report.isUsable(), report);
    }

    @Override
    public synchronized AgentRuntimeFeatureState enable(AgentRuntimeEnvironmentRequest environment) {
        try {
            installer.install(environment);
            AgentRuntimeEnvironmentReport report = environmentChecker.inspect(environment);
            if (!report.isUsable()) {
                flagStorage.setEnabled(runtimeType(), false);
                return new AgentRuntimeFeatureState(runtimeType(), false, false, report);
            }
            flagStorage.setEnabled(runtimeType(), true);
            return new AgentRuntimeFeatureState(runtimeType(), true, true, report);
        } catch (IOException | RuntimeException error) {
            flagStorage.setEnabled(runtimeType(), false);
            AgentRuntimeEnvironmentReport report = new AgentRuntimeEnvironmentReport(
                    runtimeType(), AgentRuntimeEnvironmentStatus.BLOCKED, null,
                    environment.operatingSystem(), environment.architecture(), List.of("INSTALL_FAILED"),
                    Map.of("reason", java.util.Objects.toString(
                            error.getMessage(), error.getClass().getSimpleName())), LocalDateTime.now());
            return new AgentRuntimeFeatureState(runtimeType(), false, false, report);
        }
    }

    @Override
    public synchronized AgentRuntimeFeatureState disable(AgentRuntimeEnvironmentRequest environment) {
        flagStorage.setEnabled(runtimeType(), false);
        return check(environment);
    }

    public boolean isEnabled() {
        return flagStorage.isEnabled(runtimeType());
    }
}
