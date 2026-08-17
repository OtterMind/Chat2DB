package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunTerminalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApproval;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunClaimRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalDecisionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;

import java.util.List;

public interface IAgentRuntimeDispatchService {

    AgentRuntimeRunClaim claim(String instanceId, AgentRuntimeRunClaimRequest request);

    AgentRuntimeTaskScope authorizeTaskToken(String runId, String taskToken);

    AgentRuntimeLeaseStatus renewLease(String runId, String leaseToken, AgentRuntimeLeaseRenewRequest request);

    AgentRuntimeLeaseStatus markStarted(String runId, String leaseToken, AgentRuntimeRunStartedRequest request);

    AgentRuntimeEventAccepted appendEvent(String runId, String leaseToken, AgentRuntimeEventRequest request);

    AgentRuntimeArtifactResult uploadArtifact(String runId, String leaseToken,
                                              AgentRuntimeArtifactUploadRequest request);

    AgentRuntimeApprovalResult requestApproval(String runId, String leaseToken,
                                               AgentRuntimeApprovalRequest request);

    AgentRuntimeApprovalResult getApprovalStatus(String runId, String leaseToken,
                                                 AgentRuntimeApprovalAckRequest request);

    AgentRuntimeApprovalResult acknowledgeApproval(String runId, String leaseToken,
                                                   AgentRuntimeApprovalAckRequest request);

    List<AgentRuntimeApproval> listApprovals(String runId);

    AgentRuntimeApproval getApproval(String approvalId);

    AgentRuntimeApproval decideApproval(AgentRuntimeApprovalDecisionRequest request);

    AgentRuntimeRunTerminalResult complete(String runId, String leaseToken,
                                           AgentRuntimeRunCompleteRequest request);

    AgentRuntimeRunTerminalResult fail(String runId, String leaseToken,
                                       AgentRuntimeRunFailRequest request);

    AgentRuntimeRunTerminalResult acknowledgeCancellation(String runId, String leaseToken,
                                                           AgentRuntimeRunCancelAckRequest request);

    AgentRun requestCancellation(String runId);

    int reconcileExpiredLeases(int limit);
}
