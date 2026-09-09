package ai.chat2db.community.domain.api.service.ai;

import ai.chat2db.community.domain.api.model.ai.AiSessionSummary;

import java.util.List;

public interface AiSessionFacadeService {

    List<AiSessionSummary> listSessions(Long userId);

    AiSessionSummary getSession(String sessionId, Long userId, int sessionVersion);
}
