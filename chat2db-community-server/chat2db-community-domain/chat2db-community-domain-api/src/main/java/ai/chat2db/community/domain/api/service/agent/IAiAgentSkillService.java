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

    /** Mutable user sources; packaged resources and snapshots below it remain protected. */
    default Path userDirectory() { return null; }

    default Path resourceDirectory() { return null; }

    default Path resolveLegacyPath(Path path) { return path; }

    AiAgentSkillResolveResponse resolve(AiAgentSkillResolveRequest aiAgentSkillResolveRequest);
}
