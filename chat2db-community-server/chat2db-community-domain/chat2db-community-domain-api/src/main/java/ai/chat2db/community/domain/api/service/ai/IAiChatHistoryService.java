package ai.chat2db.community.domain.api.service.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;

import java.util.List;

/**
 * Manages AI chat sessions and chat history.
 */
public interface IAiChatHistoryService {
    /**
     * Creates an AI chat session for a user.
     *
     * @param userId user identifier that owns the session.
     * @param firstMessage first message used to initialize the session title or context.
     * @return created chat session.
     */
    AiChatSession createSession(Long userId, String firstMessage);

    /**
     * Adds a message to an AI chat session.
     *
     * @param aiChatMessageAddRequest chat message creation parameters.
     * @return created chat message.
     */
    AiChatMessage addMessage(AiChatMessageAddRequest aiChatMessageAddRequest);

    /**
     * Lists AI chat sessions for a user.
     *
     * @param userId user identifier that owns the sessions.
     * @return chat sessions visible to the user.
     */
    List<AiChatSession> listSessions(Long userId);

    /**
     * Renames an AI chat session without changing its activity time.
     *
     * @param sessionId chat session identifier.
     * @param userId user identifier that owns the session.
     * @param title new session title.
     */
    void renameSession(String sessionId, Long userId, String title);

    /**
     * Lists persisted messages for an AI chat session.
     *
     * @param sessionId chat session identifier.
     * @param userId user identifier that owns the session.
     * @return chat messages in session order.
     */
    List<AiChatMessage> getMessages(String sessionId, Long userId);

    /**
     * Lists chat history prepared for AI model context.
     *
     * @param sessionId chat session identifier.
     * @param userId user identifier that owns the session.
     * @return chat messages included in AI context.
     */
    List<AiChatMessage> getHistoryForAI(String sessionId, Long userId);

    /**
     * Deletes an AI chat session for a user.
     *
     * @param sessionId chat session identifier.
     * @param userId user identifier that owns the session.
     */
    void deleteSession(String sessionId, Long userId);
}
