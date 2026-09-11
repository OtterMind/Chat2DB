package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.tools.exception.storage.StorageException;

import java.util.Objects;

final class AgentStorageOwnership {

    private final AgentSessionStorage sessionStorage;

    AgentStorageOwnership(AgentSessionStorage sessionStorage) {
        this.sessionStorage = Objects.requireNonNull(sessionStorage, "sessionStorage");
    }

    boolean owns(String sessionId, Long userId) {
        Objects.requireNonNull(userId, "userId");
        return sessionStorage.get(sessionId, userId) != null;
    }

    void require(String sessionId, Long userId) {
        if (!owns(sessionId, userId)) {
            throw new StorageException("Agent session does not exist or is not owned by the user");
        }
    }
}
