package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.service.agent.AgentArtifactStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.storage.StorageFileUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Component
public class LocalAgentArtifactStorage implements AgentArtifactStorage {

    private final AgentStorageOwnership ownership;
    private final AgentSnapshotStorage<AgentArtifact> snapshots;

    public LocalAgentArtifactStorage(
            AgentV2StoragePaths paths,
            StorageFileUtils storageFileUtils,
            AgentSessionStorage sessionStorage) {
        this.ownership = new AgentStorageOwnership(sessionStorage);
        this.snapshots = new AgentSnapshotStorage<>(
                paths,
                storageFileUtils,
                "artifacts",
                AgentArtifact.class,
                AgentArtifact::id,
                AgentArtifact::sessionId,
                this::validateArtifact);
    }

    @Override
    public synchronized AgentArtifact create(AgentArtifact artifact, Long userId) {
        ownership.require(artifact.sessionId(), userId);
        return snapshots.create(artifact);
    }

    @Override
    public synchronized AgentArtifact get(String sessionId, String artifactId, Long userId) {
        return ownership.owns(sessionId, userId) ? snapshots.get(sessionId, artifactId) : null;
    }

    @Override
    public synchronized List<AgentArtifact> list(String sessionId, Long userId) {
        if (!ownership.owns(sessionId, userId)) {
            return List.of();
        }
        return snapshots.list(sessionId).stream()
                .sorted(Comparator.comparing(AgentArtifact::gmtCreate).thenComparing(AgentArtifact::id))
                .toList();
    }

    private void validateArtifact(AgentArtifact artifact) {
        String reference = artifact.storageReference();
        boolean invalidSegment = Arrays.stream(reference.split("/", -1))
                .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."));
        if (reference.startsWith("/") || reference.contains("\\") || reference.contains(":") || invalidSegment) {
            throw new IllegalArgumentException("Agent artifact storageReference must be relative");
        }
    }
}
