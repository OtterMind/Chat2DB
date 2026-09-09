package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.ai.ChatAttachment;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiSelectedKnowledge;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.I18nUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@Slf4j
public class AiChatHistoryServiceImpl implements IAiChatHistoryService {


    public static final int MAX_HISTORY_ROUNDS = 5;

    private static final int TITLE_MAX_LEN = 50;
    private final ObjectMapper objectMapper;
    private final Path baseDir;

    @Autowired
    public AiChatHistoryServiceImpl(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get(ConfigUtils.getBasePath(), "ai-chat-history"));
    }

    AiChatHistoryServiceImpl(ObjectMapper objectMapper, Path baseDir) {
        this.objectMapper = objectMapper;
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
    }

    @Override
    public AiChatSession createSession(Long userId, String firstMessage) {
        String title = truncate(firstMessage, TITLE_MAX_LEN);
        return createSessionLocal(userId, title);
    }


    @Override
    public AiChatMessage addMessage(AiChatMessageAddRequest addAiChatMessageRequest) {
        String sessionId = addAiChatMessageRequest == null ? null : addAiChatMessageRequest.getSessionId();
        Long userId = addAiChatMessageRequest == null ? null : addAiChatMessageRequest.getUserId();
        String role = addAiChatMessageRequest == null ? null : addAiChatMessageRequest.getRole();
        String content = addAiChatMessageRequest == null ? null : addAiChatMessageRequest.getContent();
        String reasoningContent = addAiChatMessageRequest == null ? null : addAiChatMessageRequest.getReasoningContent();
        List<ChatAttachment> attachments = addAiChatMessageRequest == null ? null : addAiChatMessageRequest.getAttachments();
        List<AiSelectedKnowledge> selectedKnowledge = addAiChatMessageRequest == null
                ? null : addAiChatMessageRequest.getSelectedKnowledge();
        return addMessageLocal(sessionId, userId, role, content, reasoningContent, attachments, selectedKnowledge);
    }


    @Override
    public List<AiChatSession> listSessions(Long userId) {
        return listSessionsLocal(userId);
    }

    @Override
    public void renameSession(String sessionId, Long userId, String title) {
        renameSessionLocal(sessionId, userId, title);
    }


    @Override
    public List<AiChatMessage> getMessages(String sessionId, Long userId) {
        return getMessagesLocal(sessionId, userId);
    }


    @Override
    public List<AiChatMessage> getHistoryForAI(String sessionId, Long userId) {
        return getHistoryForAILocal(sessionId, userId);
    }


    @Override
    public void deleteSession(String sessionId, Long userId) {
        deleteSessionLocal(sessionId, userId);
    }


    private static String truncate(String text, int maxLen) {
        if (StringUtils.isBlank(text)) {
            return I18nUtils.getMessage("ai.chat.history.defaultTitle");
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen) + "…";
    }

    private synchronized AiChatSession createSessionLocal(Long userId, String title) {
        AiChatSession session = new AiChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title);
        session.setGmtCreate(LocalDateTime.now());
        session.setGmtModified(LocalDateTime.now());

        List<AiChatSession> sessions = loadSessions(userId);
        sessions.add(0, session);
        persistSessions(userId, sessions);
        return session;
    }

    private synchronized AiChatMessage addMessageLocal(String sessionId, Long userId, String role, String content,
                                                       String reasoningContent,
                                                       List<ChatAttachment> attachments,
                                                       List<AiSelectedKnowledge> selectedKnowledge) {
        sessionId = canonicalSessionId(sessionId);
        if (!ownsSession(userId, sessionId)) {
            throw new BusinessException("ai.chat.history.sessionNotOwned", new Object[]{sessionId});
        }
        AiChatMessage message = new AiChatMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setReasoningContent(reasoningContent);
        if (attachments != null) {
            message.setAttachments(new ArrayList<>(attachments));
        }
        if (selectedKnowledge != null) {
            message.setSelectedKnowledge(new ArrayList<>(selectedKnowledge));
        }
        message.setGmtCreate(LocalDateTime.now());

        List<AiChatMessage> messages = loadMessages(sessionId);
        messages.add(message);
        persistMessages(sessionId, messages);
        touchSession(userId, sessionId);
        return message;
    }

    private synchronized List<AiChatSession> listSessionsLocal(Long userId) {
        return loadSessions(userId).stream()
                .sorted(Comparator.comparing(AiChatSession::getGmtModified,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    private synchronized void renameSessionLocal(String sessionId, Long userId, String title) {
        List<AiChatSession> sessions = loadSessions(userId);
        AiChatSession session = sessions.stream()
                .filter(item -> Objects.equals(item.getId(), sessionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "ai.chat.history.sessionNotOwned", new Object[]{sessionId}));
        session.setTitle(title.trim());
        persistSessions(userId, sessions);
    }

    private synchronized List<AiChatMessage> getMessagesLocal(String sessionId, Long userId) {
        if (!ownsSession(userId, sessionId)) {
            return new ArrayList<>();
        }
        sessionId = canonicalSessionId(sessionId);
        return loadMessages(sessionId);
    }

    private boolean ownsSession(Long userId, String sessionId) {
        return loadSessions(userId).stream().anyMatch(s -> Objects.equals(s.getId(), sessionId));
    }

    private synchronized List<AiChatMessage> getHistoryForAILocal(String sessionId, Long userId) {
        List<AiChatMessage> all = getMessagesLocal(sessionId, userId);
        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (all.size() <= maxMessages) {
            return new ArrayList<>(all);
        }
        return all.subList(all.size() - maxMessages, all.size());
    }

    private synchronized void deleteSessionLocal(String sessionId, Long userId) {
        if (!ownsSession(userId, sessionId)) {
            return;
        }
        canonicalSessionId(sessionId);
        List<AiChatSession> sessions = loadSessions(userId);
        // Only delete the message file when the session was actually owned by
        // this user; otherwise a caller could delete another user's file by id.
        boolean removed = sessions.removeIf(s -> Objects.equals(s.getId(), sessionId));
        persistSessions(userId, sessions);
        if (!removed) {
            return;
        }

        Path msgFile = messagesPath(sessionId);
        try {
            Files.deleteIfExists(msgFile);
        } catch (IOException e) {
            throw new BusinessException("ai.chat.history.deleteMessagesFailed", new Object[]{msgFile, e.getMessage()}, e);
        }
    }

    private Path sessionsPath(Long userId) {
        return resolveHistoryFile("sessions-" + userId + ".json");
    }

    private Path messagesPath(String sessionId) {
        return resolveHistoryFile(canonicalSessionId(sessionId) + ".json");
    }

    private Path resolveHistoryFile(String fileName) {
        Path target = baseDir.resolve(fileName).normalize();
        if (!baseDir.equals(target.getParent())) {
            throw new IllegalArgumentException("AI chat history file must stay inside its storage directory");
        }
        return target;
    }

    private String canonicalSessionId(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new BusinessException("ai.chat.history.sessionNotOwned", new Object[]{sessionId});
        }
        try {
            String canonical = UUID.fromString(sessionId).toString();
            if (!canonical.equals(sessionId)) {
                throw new IllegalArgumentException("Session id is not canonical");
            }
            return canonical;
        } catch (IllegalArgumentException e) {
            throw new BusinessException("ai.chat.history.sessionNotOwned", new Object[]{sessionId}, e);
        }
    }

    private List<AiChatSession> loadSessions(Long userId) {
        Path path = sessionsPath(userId);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            SessionsFile file = objectMapper.readValue(path.toFile(), SessionsFile.class);
            return file.getSessions() != null ? file.getSessions() : new ArrayList<>();
        } catch (Exception e) {
            throw new BusinessException("ai.chat.history.loadSessionsFailed", new Object[]{path, e.getMessage()}, e);
        }
    }

    private void persistSessions(Long userId, List<AiChatSession> sessions) {
        Path path = sessionsPath(userId);
        try {
            SessionsFile file = new SessionsFile();
            file.setSessions(sessions);
            writeAtomically(path, file);
        } catch (IOException e) {
            throw new BusinessException("ai.chat.history.persistSessionsFailed", new Object[]{path, e.getMessage()}, e);
        }
    }

    private List<AiChatMessage> loadMessages(String sessionId) {
        Path path = messagesPath(sessionId);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            MessagesFile file = objectMapper.readValue(path.toFile(), MessagesFile.class);
            return file.getMessages() != null ? file.getMessages() : new ArrayList<>();
        } catch (Exception e) {
            throw new BusinessException("ai.chat.history.loadMessagesFailed", new Object[]{path, e.getMessage()}, e);
        }
    }

    private void persistMessages(String sessionId, List<AiChatMessage> messages) {
        Path path = messagesPath(sessionId);
        try {
            MessagesFile file = new MessagesFile();
            file.setMessages(messages);
            writeAtomically(path, file);
        } catch (IOException e) {
            throw new BusinessException("ai.chat.history.persistMessagesFailed", new Object[]{path, e.getMessage()}, e);
        }
    }

    private void touchSession(Long userId, String sessionId) {
        List<AiChatSession> sessions = loadSessions(userId);
        sessions.stream()
                .filter(s -> Objects.equals(s.getId(), sessionId))
                .findFirst()
                .ifPresent(s -> s.setGmtModified(LocalDateTime.now()));
        persistSessions(userId, sessions);
    }

    private void writeAtomically(Path target, Object value) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        if (!baseDir.equals(absoluteTarget.getParent())) {
            throw new IOException("AI chat history target must stay inside its storage directory");
        }
        Files.createDirectories(baseDir);
        Path temporary = Files.createTempFile(baseDir, ".chat-history-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, absoluteTarget, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Data
    public static class SessionsFile {
        private List<AiChatSession> sessions = new ArrayList<>();
    }

    @Data
    public static class MessagesFile {
        private List<AiChatMessage> messages = new ArrayList<>();
    }
}
