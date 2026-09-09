package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentFeature;
import ai.chat2db.community.domain.api.model.agent.AgentFeatureState;

public interface AgentFeatureService {

    AgentFeature feature();

    AgentFeatureState check();

    AgentFeatureState enable();

    AgentFeatureState disable();
}
