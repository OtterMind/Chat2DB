package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.service.agent.AgentRunStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.storage.StorageFileUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class LocalAgentRunStorage implements AgentRunStorage {

    private final AgentStorageOwnership ownership;
    private final AgentSnapshotStorage<AgentRun> snapshots;

    public LocalAgentRunStorage(
            AgentV2StoragePaths paths,
            StorageFileUtils storageFileUtils,
            AgentSessionStorage sessionStorage) {
        this.ownership = new AgentStorageOwnership(sessionStorage);
        this.snapshots = new AgentSnapshotStorage<>(
                paths,
                storageFileUtils,
                "runs",
                AgentRun.class,
                AgentRun::id,
                AgentRun::sessionId,
                run -> {
                });
    }

    @Override
    public synchronized AgentRun create(AgentRun run, Long userId) {
        ownership.require(run.sessionId(), userId);
        return snapshots.create(run);
    }

    @Override
    public synchronized AgentRun get(String sessionId, String runId, Long userId) {
        return ownership.owns(sessionId, userId) ? snapshots.get(sessionId, runId) : null;
    }

    @Override
    public synchronized List<AgentRun> list(String sessionId, Long userId) {
        if (!ownership.owns(sessionId, userId)) {
            return List.of();
        }
        return snapshots.list(sessionId).stream()
                .sorted(Comparator.comparingLong(AgentRun::firstEventSequence).thenComparing(AgentRun::id))
                .toList();
    }

    @Override
    public synchronized boolean compareAndSet(AgentRun run, AgentRunStatus expectedStatus, Long userId) {
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        ownership.require(run.sessionId(), userId);
        AgentRun existing = snapshots.get(run.sessionId(), run.id());
        if (existing == null || existing.status() != expectedStatus) {
            return false;
        }
        if (!Objects.equals(existing.sessionId(), run.sessionId())
                || !Objects.equals(existing.model(), run.model())
                || !Objects.equals(existing.requestMessageId(), run.requestMessageId())
                || !Objects.equals(existing.idempotencyKey(), run.idempotencyKey())
                || existing.firstEventSequence() != run.firstEventSequence()) {
            throw new IllegalArgumentException("Agent run identity cannot be changed");
        }
        snapshots.update(run);
        return true;
    }
}
