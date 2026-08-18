package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunTerminalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeTaskScope;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeHeartbeatRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeInstanceRegisterRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunClaimRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunSuspendRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeControlService;
import ai.chat2db.community.domain.api.service.agent.IAgentRuntimeDispatchService;
import ai.chat2db.community.domain.api.service.ai.IAiToolService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.util.AgentRuntimeDaemonUtils;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/runtime/daemon")
public class AgentRuntimeDaemonController {

    private final IAgentRuntimeControlService runtimeService;
    private final IAgentRuntimeDispatchService dispatchService;
    private final IAiToolService aiToolService;

    public AgentRuntimeDaemonController(IAgentRuntimeControlService runtimeService,
                                        IAgentRuntimeDispatchService dispatchService,
                                        IAiToolService aiToolService) {
        this.runtimeService = runtimeService;
        this.dispatchService = dispatchService;
        this.aiToolService = aiToolService;
    }

    @PostMapping("/instances/register")
    public DataResult<AgentRuntimeInstance> register(
            @RequestBody AgentRuntimeInstanceRegisterRequest request) {
        return DataResult.of(runtimeService.register(request));
    }

    @PostMapping("/instances/{instanceId}/heartbeat")
    public DataResult<AgentRuntimeInstance> heartbeat(
            @PathVariable String instanceId, @RequestBody AgentRuntimeHeartbeatRequest request) {
        return DataResult.of(runtimeService.heartbeat(instanceId, request));
    }

    @PostMapping("/instances/{instanceId}/runs/claim")
    public DataResult<AgentRuntimeRunClaim> claim(
            @PathVariable String instanceId, @RequestBody AgentRuntimeRunClaimRequest request) {
        return DataResult.of(dispatchService.claim(instanceId, request));
    }

    @PostMapping("/runs/{runId}/lease/renew")
    public DataResult<AgentRuntimeLeaseStatus> renewLease(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeLeaseRenewRequest request) {
        return DataResult.of(dispatchService.renewLease(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/started")
    public DataResult<AgentRuntimeLeaseStatus> markStarted(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeRunStartedRequest request) {
        return DataResult.of(dispatchService.markStarted(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/suspend-for-sql-approval")
    public DataResult<AgentRuntimeLeaseStatus> suspendForSqlApproval(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeRunSuspendRequest request) {
        return DataResult.of(dispatchService.suspendForSqlApproval(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/events")
    public DataResult<AgentRuntimeEventAccepted> appendEvent(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeEventRequest request) {
        return DataResult.of(dispatchService.appendEvent(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/artifacts")
    public DataResult<AgentRuntimeArtifactResult> uploadArtifact(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeArtifactUploadRequest request) {
        return DataResult.of(dispatchService.uploadArtifact(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/approvals/request")
    public DataResult<AgentRuntimeApprovalResult> requestApproval(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeApprovalRequest request) {
        return DataResult.of(dispatchService.requestApproval(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/approvals/status")
    public DataResult<AgentRuntimeApprovalResult> approvalStatus(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeApprovalAckRequest request) {
        return DataResult.of(dispatchService.getApprovalStatus(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/approvals/ack")
    public DataResult<AgentRuntimeApprovalResult> acknowledgeApproval(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeApprovalAckRequest request) {
        return DataResult.of(dispatchService.acknowledgeApproval(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/complete")
    public DataResult<AgentRuntimeRunTerminalResult> complete(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeRunCompleteRequest request) {
        return DataResult.of(dispatchService.complete(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/fail")
    public DataResult<AgentRuntimeRunTerminalResult> fail(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeRunFailRequest request) {
        return DataResult.of(dispatchService.fail(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/cancel-ack")
    public DataResult<AgentRuntimeRunTerminalResult> acknowledgeCancellation(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.RUN_LEASE_HEADER) String leaseToken,
            @RequestBody AgentRuntimeRunCancelAckRequest request) {
        return DataResult.of(dispatchService.acknowledgeCancellation(runId, leaseToken, request));
    }

    @PostMapping("/runs/{runId}/tools/execute-sql")
    public DataResult<String> executeSql(
            @PathVariable String runId,
            @RequestHeader(AgentRuntimeDaemonUtils.TASK_TOKEN_HEADER) String taskToken,
            @RequestBody AiExecuteSqlRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("runtime SQL tool request is required");
        }
        AgentRuntimeTaskScope scope = dispatchService.authorizeTaskToken(runId, taskToken);
        AiToolContextRequest trustedContext = new AiToolContextRequest();
        trustedContext.setAgentRunId(scope.getRunId());
        trustedContext.setAgentDataScopes(scope.getDataScopes());
        trustedContext.setWaitForApprovalDecision(false);
        request.setAiToolContextRequest(trustedContext);
        return DataResult.of(aiToolService.executeSql(request));
    }
}
