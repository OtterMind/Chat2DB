package ai.chat2db.community.domain.core.converter.agent;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill;

public final class AgentSkillConverter {
    private AgentSkillConverter() { }

    public static AgentRuntimeSkill skill2runtime(AiAgentSkill skill) {
        return new AgentRuntimeSkill(skill.name(), skill.entryPath(), skill.digest());
    }
}
