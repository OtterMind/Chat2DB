package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentSkillResolveResponse;
import java.util.List;
import java.nio.file.Path;

public interface IAiAgentSkillService {
    List<AiAgentSkill> prepare();

    /** Freeze the resources a session may read until its next run. */
    default List<AiAgentSkill> select(String sessionId) { return prepare(); }

    default List<AiAgentSkill> selected(String sessionId) { return prepare(); }

    default void release(String sessionId) { }

    /** Mutable user sources, edited in place. */
    default Path userDirectory() { return null; }

    /** Installed packaged skills; read-only and never a user installation destination. */
    default Path resourceDirectory() { return null; }

    AiAgentSkillResolveResponse resolve(AiAgentSkillResolveRequest aiAgentSkillResolveRequest);
}
