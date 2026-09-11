package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.exception.storage.StorageException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class LocalAgentSessionStorage implements AgentSessionStorage {

    private static final int STORAGE_SCHEMA_VERSION = 2;

    private final AgentV2StoragePaths paths;
    private final StorageFileUtils storageFileUtils;

    public LocalAgentSessionStorage(AgentV2StoragePaths paths, StorageFileUtils storageFileUtils) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.storageFileUtils = Objects.requireNonNull(storageFileUtils, "storageFileUtils");
    }

    @Override
    public synchronized AgentSession create(AgentSession session) {
        validateSession(session);
        ensureStorage();
        Path sessionDirectory = paths.sessionDirectory(session.id());
        Path sessionFile = paths.sessionFile(session.id());
        storageFileUtils.rejectSymbolicLink(sessionDirectory);
        storageFileUtils.rejectSymbolicLink(sessionFile);
        if (Files.exists(sessionDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Agent session already exists: " + session.id());
        }
        storageFileUtils.createPrivateDirectory(sessionDirectory);
        storageFileUtils.verifyInsideRoot(paths.root(), sessionDirectory);
        try {
            writeSession(sessionFile, session);
        } catch (RuntimeException exception) {
            storageFileUtils.deleteEmptyDirectory(sessionDirectory);
            throw exception;
        }
        return session;
    }

    @Override
    public synchronized AgentSession get(String sessionId, Long userId) {
        Objects.requireNonNull(userId, "userId");
        Path sessionFile = paths.sessionFile(sessionId);
        if (!Files.exists(sessionFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        validateExistingStorage();
        AgentSession session = readSession(sessionFile);
        return Objects.equals(userId, session.userId()) ? session : null;
    }

    @Override
    public synchronized List<AgentSession> listByUserId(Long userId) {
        Objects.requireNonNull(userId, "userId");
        Path sessionsDirectory = paths.sessionsDirectory();
        if (!Files.exists(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        validateExistingStorage();
        storageFileUtils.rejectSymbolicLink(sessionsDirectory);
        try (Stream<Path> entries = Files.list(sessionsDirectory)) {
            return entries
                    .peek(storageFileUtils::rejectSymbolicLink)
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> readSession(path.resolve("session.json")))
                    .filter(session -> Objects.equals(userId, session.userId()))
                    .sorted(Comparator.comparing(
                            AgentSession::gmtModified,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(AgentSession::id))
                    .toList();
        } catch (IOException exception) {
            throw new StorageException("Failed to list V2 agent sessions", exception);
        }
    }

    @Override
    public synchronized boolean compareAndSet(AgentSession session, AgentSessionStatus expectedStatus) {
        validateSession(session);
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Path sessionFile = paths.sessionFile(session.id());
        if (!Files.exists(sessionFile, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        validateExistingStorage();
        AgentSession existing = readSession(sessionFile);
        if (!Objects.equals(existing.userId(), session.userId())) {
            throw new StorageException("Agent session owner cannot be changed: " + session.id());
        }
        AgentDefinition definition = existing.definition();
        String modelConfigId = session.definition().modelConfigId();
        AgentDefinition selectedDefinition = new AgentDefinition(definition.id(), definition.name(),
                definition.description(), definition.systemPrompt(), definition.runtimeType(), modelConfigId,
                definition.revision() + (definition.modelConfigId().equals(modelConfigId) ? 0 : 1));
        if (!Objects.equals(selectedDefinition, session.definition())
                || !Objects.equals(existing.runtimeBinding(), session.runtimeBinding())
                || !Objects.equals(existing.gmtCreate(), session.gmtCreate())) {
            throw new IllegalArgumentException("Agent session identity cannot be changed");
        }
        if (existing.status() != expectedStatus) {
            return false;
        }
        writeSession(sessionFile, session);
        return true;
    }

    @Override
    public synchronized AgentSession rename(String sessionId, Long userId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        AgentSession session = get(sessionId, userId);
        if (session == null) {
            throw new IllegalArgumentException("Agent session does not exist");
        }
        AgentSession renamed = new AgentSession(
                session.schemaVersion(), session.id(), session.userId(), session.definition(),
                session.runtimeBinding(), session.status(), title.trim(), session.lastEventSequence(),
                session.gmtCreate(), LocalDateTime.now());
        writeSession(paths.sessionFile(sessionId), renamed);
        return renamed;
    }

    @Override
    public synchronized void delete(String sessionId, Long userId) {
        AgentSession session = get(sessionId, userId);
        if (session == null) {
            throw new IllegalArgumentException("Agent session does not exist");
        }
        storageFileUtils.deleteTree(paths.root(), paths.sessionDirectory(sessionId));
    }

    private void ensureStorage() {
        storageFileUtils.createPrivateDirectory(paths.root());
        storageFileUtils.rejectSymbolicLink(paths.root());
        ensureSchema();
        storageFileUtils.createPrivateDirectory(paths.sessionsDirectory());
        storageFileUtils.rejectSymbolicLink(paths.sessionsDirectory());
        storageFileUtils.verifyInsideRoot(paths.root(), paths.sessionsDirectory());
    }

    private void validateExistingStorage() {
        storageFileUtils.rejectSymbolicLink(paths.root());
        validateSchema();
    }

    private void ensureSchema() {
        Path schemaFile = paths.schemaFile();
        storageFileUtils.rejectSymbolicLink(schemaFile);
        if (Files.exists(schemaFile, LinkOption.NOFOLLOW_LINKS)) {
            validateSchema();
            return;
        }
        JSONObject schema = new JSONObject();
        schema.put("schemaVersion", STORAGE_SCHEMA_VERSION);
        storageFileUtils.writeAtomically(schemaFile, JSON.toJSONString(schema));
    }

    private void validateSchema() {
        Path schemaFile = paths.schemaFile();
        storageFileUtils.rejectSymbolicLink(schemaFile);
        if (!Files.isRegularFile(schemaFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Missing V2 agent storage schema");
        }
        try {
            JSONObject schema = JSON.parseObject(Files.readString(schemaFile, StandardCharsets.UTF_8));
            if (schema == null || schema.getIntValue("schemaVersion") != STORAGE_SCHEMA_VERSION) {
                throw new StorageException("Unsupported V2 agent storage schema");
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof StorageException storageException) {
                throw storageException;
            }
            throw new StorageException("Failed to read V2 agent storage schema", exception);
        }
    }

    private AgentSession readSession(Path sessionFile) {
        storageFileUtils.rejectSymbolicLink(sessionFile.getParent());
        storageFileUtils.rejectSymbolicLink(sessionFile);
        storageFileUtils.verifyInsideRoot(paths.root(), sessionFile);
        if (!Files.isRegularFile(sessionFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException("Missing V2 agent session file: " + sessionFile.getFileName());
        }
        try {
            AgentSession session = JSON.parseObject(
                    Files.readString(sessionFile, StandardCharsets.UTF_8), AgentSession.class);
            validateSession(session);
            return session;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof StorageException storageException) {
                throw storageException;
            }
            throw new StorageException("Failed to read V2 agent session", exception);
        }
    }

    private void writeSession(Path sessionFile, AgentSession session) {
        storageFileUtils.rejectSymbolicLink(sessionFile.getParent());
        storageFileUtils.rejectSymbolicLink(sessionFile);
        storageFileUtils.verifyInsideRoot(paths.root(), sessionFile);
        storageFileUtils.writeAtomically(sessionFile, JSON.toJSONString(session));
    }

    private void validateSession(AgentSession session) {
        Objects.requireNonNull(session, "session");
        if (session.schemaVersion() != AgentSession.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Agent session must use schema version " + AgentSession.SCHEMA_VERSION);
        }
        if (session.gmtModified() == null || session.gmtCreate() == null
                || session.gmtModified().isBefore(session.gmtCreate())) {
            throw new IllegalArgumentException("Agent session timestamps are invalid");
        }
        paths.sessionDirectory(session.id());
    }
}
