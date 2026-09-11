package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.domain.api.service.agent.IAgentQueryResultStorage;
import ai.chat2db.community.storage.StorageFileUtils;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AgentQueryResultStorageImpl implements IAgentQueryResultStorage {
    private final AgentStorageOwnership ownership;
    private final AgentSnapshotStorage<DbAgentQueryResult> snapshots;

    public AgentQueryResultStorageImpl(AgentV2StoragePaths paths, StorageFileUtils files, AgentSessionStorage sessions) {
        ownership = new AgentStorageOwnership(sessions);
        snapshots = new AgentSnapshotStorage<>(paths, files, "query-results", DbAgentQueryResult.class,
                DbAgentQueryResult::id, DbAgentQueryResult::sessionId, value -> Objects.requireNonNull(value.data()));
    }

    @Override
    public synchronized void create(DbAgentQueryResult result, Long userId) {
        ownership.require(result.sessionId(), userId);
        snapshots.create(result);
    }

    @Override
    public synchronized DbAgentQueryResult get(String sessionId, String resultId, Long userId) {
        return ownership.owns(sessionId, userId) ? snapshots.get(sessionId, resultId) : null;
    }
}
