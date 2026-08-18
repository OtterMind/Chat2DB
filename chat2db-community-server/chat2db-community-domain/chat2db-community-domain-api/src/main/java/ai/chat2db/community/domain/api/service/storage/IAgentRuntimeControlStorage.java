package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;

import java.util.List;

public interface IAgentRuntimeControlStorage {

    AgentRuntimeProfile createRuntimeProfile(AgentRuntimeProfile profile);

    AgentRuntimeProfile updateRuntimeProfile(AgentRuntimeProfile profile, long expectedRevision);

    AgentRuntimeProfile getRuntimeProfile(String id);

    List<AgentRuntimeProfile> listRuntimeProfiles();

    AgentRuntimeInstance createRuntimeInstance(AgentRuntimeInstance instance);

    AgentRuntimeInstance updateRuntimeInstance(AgentRuntimeInstance instance, long expectedRevision);

    AgentRuntimeInstance heartbeatRuntimeInstance(String instanceId, String daemonId,
                                                   AgentRuntimeInstanceStatusEnum status,
                                                   java.util.Date heartbeatAt);

    AgentRuntimeInstance getRuntimeInstance(String id);

    AgentRuntimeInstance findRuntimeInstance(String daemonId, AgentRuntimeProviderEnum provider);

    List<AgentRuntimeInstance> listRuntimeInstances();

    AgentRuntimeRunLease claimRuntimeRun(String instanceId, AgentRuntimeProviderEnum provider,
                                         String leaseTokenHash, String taskTokenHash,
                                         java.util.Date claimedAt, java.util.Date leaseExpiresAt);

    AgentRuntimeRunLease getRuntimeRunLease(String runId);

    AgentRuntimeRunLease updateRuntimeRunLease(AgentRuntimeRunLease lease, long expectedRevision);

    AgentRuntimeRunLease startRuntimeRun(AgentRuntimeRunLease lease, long expectedLeaseRevision,
                                         long expectedRunRevision);

    AgentRunEvent appendRuntimeRunEvent(AgentRunEvent event, int leaseAttempt,
                                        long runtimeSequence, java.util.Date acceptedAt,
                                        String providerSessionId);

    AgentRuntimeRunLease requestRuntimeRunCancellation(String runId, java.util.Date requestedAt);

    AgentRuntimeApproval createOrGetRuntimeApproval(AgentRuntimeApproval approval);

    AgentRuntimeApproval getRuntimeApproval(String approvalId);

    AgentRuntimeApproval findRuntimeApproval(String runId, int leaseAttempt, String providerRequestId);

    List<AgentRuntimeApproval> listRuntimeApprovals(String runId);

    AgentRuntimeApproval updateRuntimeApproval(AgentRuntimeApproval approval, long expectedRevision);

    AgentRuntimeRunLease finishRuntimeRun(AgentRuntimeRunLease lease, AgentRunEvent terminalEvent,
                                          AgentRunStatusEnum targetStatus, String failureReason,
                                          String resultSummary, java.util.Date completedAt,
                                          long expectedLeaseRevision, long expectedRunRevision);

    AgentRuntimeRunLease suspendRuntimeRun(AgentRuntimeRunLease lease, AgentRunEvent suspendEvent,
                                           AgentRunStatusEnum targetRunStatus, java.util.Date suspendedAt,
                                           long expectedLeaseRevision, long expectedRunRevision);

    List<String> reconcileExpiredRuntimeRuns(java.util.Date expiredAt, int limit);
}
