package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentFeature;
import ai.chat2db.community.domain.api.model.agent.AgentFeatureState;
import ai.chat2db.community.domain.api.service.agent.AgentFeatureService;

public class BashAgentFeatureService implements AgentFeatureService {

    private final AgentFeatureFlagStorage flagStorage;
    private final BashEnvironmentChecker environmentChecker;

    public BashAgentFeatureService(
            AgentFeatureFlagStorage flagStorage,
            BashEnvironmentChecker environmentChecker) {
        this.flagStorage = flagStorage;
        this.environmentChecker = environmentChecker;
    }

    @Override
    public AgentFeature feature() {
        return AgentFeature.BASH;
    }

    @Override
    public AgentFeatureState check() {
        return environmentChecker.check(flagStorage.isEnabled(feature()));
    }

    @Override
    public synchronized AgentFeatureState enable() {
        AgentFeatureState environment = environmentChecker.check(false);
        flagStorage.setEnabled(feature(), environment.available());
        return check();
    }

    @Override
    public synchronized AgentFeatureState disable() {
        flagStorage.setEnabled(feature(), false);
        return check();
    }
}
