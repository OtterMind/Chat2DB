package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.feature.AgentFeatureState;
import ai.chat2db.community.tools.enums.agent.AgentFeature;

public interface AgentFeatureService {

    AgentFeature feature();

    AgentFeatureState check();

    AgentFeatureState enable();

    AgentFeatureState disable();
}
