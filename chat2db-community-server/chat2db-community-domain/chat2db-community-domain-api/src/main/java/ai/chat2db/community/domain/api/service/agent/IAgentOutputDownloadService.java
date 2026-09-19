package ai.chat2db.community.domain.api.service.agent;

/** Desktop-only save dialog and streaming copy. Null means the user cancelled. */
public interface IAgentOutputDownloadService {
    String save(String sessionId, Long userId, String artifactId);
}
