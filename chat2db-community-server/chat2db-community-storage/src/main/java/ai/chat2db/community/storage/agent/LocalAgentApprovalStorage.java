package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.service.agent.AgentApprovalStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.storage.StorageFileUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class LocalAgentApprovalStorage implements AgentApprovalStorage {

    private final AgentStorageOwnership ownership;
    private final AgentSnapshotStorage<AgentApproval> snapshots;

    public LocalAgentApprovalStorage(
            AgentV2StoragePaths paths,
            StorageFileUtils storageFileUtils,
            AgentSessionStorage sessionStorage) {
        this.ownership = new AgentStorageOwnership(sessionStorage);
        this.snapshots = new AgentSnapshotStorage<>(
                paths,
                storageFileUtils,
                "approvals",
                AgentApproval.class,
                AgentApproval::id,
                AgentApproval::sessionId,
                approval -> {
                });
    }

    @Override
    public synchronized AgentApproval create(AgentApproval approval, Long userId) {
        ownership.require(approval.sessionId(), userId);
        return snapshots.create(approval);
    }

    @Override
    public synchronized AgentApproval get(String sessionId, String approvalId, Long userId) {
        return ownership.owns(sessionId, userId) ? snapshots.get(sessionId, approvalId) : null;
    }

    @Override
    public synchronized List<AgentApproval> list(String sessionId, Long userId) {
        if (!ownership.owns(sessionId, userId)) {
            return List.of();
        }
        return snapshots.list(sessionId).stream()
                .sorted(Comparator.comparing(AgentApproval::expiresAt).thenComparing(AgentApproval::id))
                .toList();
    }

    @Override
    public synchronized boolean compareAndSet(
            AgentApproval approval,
            AgentApprovalStatus expectedStatus,
            Long userId) {
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        ownership.require(approval.sessionId(), userId);
        AgentApproval existing = snapshots.get(approval.sessionId(), approval.id());
        if (existing == null) {
            return false;
        }
        if (existing.status() != expectedStatus) {
            return false;
        }
        if (!existing.runId().equals(approval.runId())
                || !existing.toolCallId().equals(approval.toolCallId())
                || !existing.subjectSha256().equals(approval.subjectSha256())
                || existing.scope() != approval.scope()
                || !existing.expiresAt().equals(approval.expiresAt())) {
            throw new IllegalArgumentException("Agent approval subject cannot be changed");
        }
        snapshots.update(approval);
        return true;
    }
}
