package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentSkillResolveResponse;
import java.util.List;

public interface IAiAgentSkillService {
    List<AiAgentSkill> prepare();

    AiAgentSkillResolveResponse resolve(AiAgentSkillResolveRequest aiAgentSkillResolveRequest);
}
