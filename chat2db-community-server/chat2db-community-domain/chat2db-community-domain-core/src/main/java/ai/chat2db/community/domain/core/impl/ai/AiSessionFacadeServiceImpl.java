package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.ai.AiSessionSummary;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.service.agent.AgentService;
import ai.chat2db.community.domain.api.service.ai.AiSessionFacadeService;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AiSessionFacadeServiceImpl implements AiSessionFacadeService {

    private final IAiChatHistoryService v1HistoryService;
    private final AgentService agentService;

    public AiSessionFacadeServiceImpl(
            IAiChatHistoryService v1HistoryService,
            AgentService agentService) {
        this.v1HistoryService = v1HistoryService;
        this.agentService = agentService;
    }

    @Override
    public List<AiSessionSummary> listSessions(Long userId) {
        List<AiSessionSummary> summaries = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();
        for (AiChatSession session : v1HistoryService.listSessions(userId)) {
            requireUnique(identifiers, session.getId());
            summaries.add(v1(session));
        }
        for (AgentSession session : agentService.listSessions(userId)) {
            requireUnique(identifiers, session.id());
            summaries.add(v2(session));
        }
        return summaries.stream()
                .sorted(Comparator.comparing(
                        AiSessionSummary::gmtModified,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public AiSessionSummary getSession(String sessionId, Long userId, int sessionVersion) {
        if (sessionVersion == 1) {
            for (AiChatSession session : v1HistoryService.listSessions(userId)) {
                if (session.getId().equals(sessionId)) {
                    return v1(session);
                }
            }
            return null;
        }
        if (sessionVersion == 2) {
            AgentSession session = agentService.getSession(sessionId, userId);
            return session == null ? null : v2(session);
        }
        throw new IllegalArgumentException("sessionVersion must be 1 or 2");
    }

    private AiSessionSummary v1(AiChatSession session) {
        return new AiSessionSummary(
                session.getId(), session.getTitle(), 1, null, null,
                null, session.getGmtCreate(), session.getGmtModified());
    }

    private AiSessionSummary v2(AgentSession session) {
        return new AiSessionSummary(
                session.id(), session.title(), 2, session.runtimeBinding().runtimeType(), session.status(),
                session.definition().modelConfigId(), session.gmtCreate(), session.gmtModified());
    }

    private void requireUnique(Set<String> identifiers, String id) {
        if (!identifiers.add(id)) {
            throw new IllegalStateException("AI session id exists in both V1 and V2 storage: " + id);
        }
    }
}
