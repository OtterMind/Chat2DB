package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentCapabilityEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlPermitDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentSqlExecutionPermit;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunLease;
import ai.chat2db.community.domain.api.model.request.agent.AgentApprovalDecisionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentSqlToolRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.service.storage.IAgentRuntimeControlStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentToolGatewayTest {

    private AgentControlServiceTest.MemoryAgentControlStorage storage;
    private AgentRunServiceImpl runService;
    private AgentTaskServiceImpl taskService;
    private AgentToolGatewayImpl gateway;
    private AgentTaskCreation creation;

    @BeforeEach
    void setUp() {
        storage = new AgentControlServiceTest.MemoryAgentControlStorage();
        AgentDefinitionServiceImpl agentService = new AgentDefinitionServiceImpl(storage);
        taskService = new AgentTaskServiceImpl(storage);
        runService = new AgentRunServiceImpl(storage);
        gateway = new AgentToolGatewayImpl(storage, runService, taskService);

        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(7L);
        scope.setDatabaseName("sales");
        scope.setSchemaName("public");
        AgentDefinitionCreateRequest agent = new AgentDefinitionCreateRequest();
        agent.setName("Safe analyst");
        agent.setCapabilities(new LinkedHashSet<>(List.of(
                AgentCapabilityEnum.DATA_READ, AgentCapabilityEnum.DATA_WRITE)));
        agent.setDataScopes(List.of(scope));
        String agentId = agentService.create(agent).getId();

        AgentTaskCreateRequest task = new AgentTaskCreateRequest();
        task.setTitle("Analyze and correct refunds");
        task.setAssigneeAgentId(agentId);
        task.setDataScopeSnapshot(List.of(scope));
        creation = taskService.create(task);
        AgentRunTransitionRequest running = new AgentRunTransitionRequest();
        running.setRunId(creation.getInitialRun().getId());
        running.setExpectedRevision(1L);
        running.setTargetStatus(AgentRunStatusEnum.RUNNING);
        runService.transition(running);
        AgentTaskTransitionRequest inProgress = new AgentTaskTransitionRequest();
        inProgress.setTaskId(creation.getTask().getId());
        inProgress.setExpectedRevision(creation.getTask().getRevision());
        inProgress.setTargetStatus(AgentTaskStatusEnum.IN_PROGRESS);
        taskService.transition(inProgress);
    }

    @Test
    void replaysCompletedReadAttemptWithoutExecutingAgain() {
        AgentSqlToolRequest request = request("read-call", "select count(*) from refunds");

        AgentSqlExecutionPermit first = gateway.prepareSql(request);
        gateway.markSucceeded(first.getAttempt().getId(), "count=3");
        AgentSqlExecutionPermit replay = gateway.prepareSql(request);

        assertEquals(AgentSqlPermitDecisionEnum.EXECUTE, first.getDecision());
        assertEquals(AgentSqlPermitDecisionEnum.REPLAY_RESULT, replay.getDecision());
        assertEquals("count=3", replay.getReplayResult());
        assertEquals(1, gateway.listAttempts(creation.getInitialRun().getId()).size());
    }

    @Test
    void requiresImmutableApprovalAndNeverRetriesUnknownWrite() {
        AgentSqlToolRequest request = request("write-call", "update refunds set status = 'REVIEW' where id = 1");

        AgentSqlExecutionPermit pending = gateway.prepareSql(request);

        assertEquals(AgentSqlPermitDecisionEnum.APPROVAL_REQUIRED, pending.getDecision());
        assertNotNull(pending.getApproval());
        assertEquals(AgentRunStatusEnum.WAITING_APPROVAL,
                runService.get(creation.getInitialRun().getId()).getStatus());
        assertEquals(AgentTaskStatusEnum.WAITING_APPROVAL,
                taskService.get(creation.getTask().getId()).getStatus());

        AgentApprovalDecisionRequest decision = new AgentApprovalDecisionRequest();
        decision.setApprovalId(pending.getApproval().getId());
        decision.setExpectedRevision(1L);
        decision.setDecision(AgentApprovalDecisionEnum.APPROVE);
        decision.setDecidedBy(9L);
        assertEquals(AgentApprovalStatusEnum.APPROVED, gateway.decide(decision).getStatus());
        assertEquals(AgentRunStatusEnum.RUNNING, runService.get(creation.getInitialRun().getId()).getStatus());
        assertEquals(AgentTaskStatusEnum.IN_PROGRESS,
                taskService.get(creation.getTask().getId()).getStatus());

        AgentSqlExecutionPermit executable = gateway.prepareSql(request);
        assertEquals(AgentSqlPermitDecisionEnum.EXECUTE, executable.getDecision());
        gateway.markFailed(executable.getAttempt().getId(), "connection reset", true);
        AgentSqlExecutionPermit retried = gateway.prepareSql(request);

        assertEquals(AgentSqlPermitDecisionEnum.DENIED, retried.getDecision());
        assertEquals(AgentToolAttemptStatusEnum.UNKNOWN, retried.getAttempt().getStatus());
        assertEquals(1, gateway.listAttempts(creation.getInitialRun().getId()).size());
    }

    @Test
    void changedSqlSupersedesPendingProposalAndExpiresApproval() {
        AgentSqlExecutionPermit first = gateway.prepareSql(
                request("call-1", "update refunds set status = 'REVIEW' where id = 1"));
        AgentRunTransitionRequest resumeForTest = new AgentRunTransitionRequest();
        resumeForTest.setRunId(creation.getInitialRun().getId());
        resumeForTest.setExpectedRevision(runService.get(creation.getInitialRun().getId()).getRevision());
        resumeForTest.setTargetStatus(AgentRunStatusEnum.RUNNING);
        runService.transition(resumeForTest);
        AgentSqlExecutionPermit second = gateway.prepareSql(
                request("call-2", "update refunds set status = 'REVIEW' where id = 2"));

        assertEquals(2, second.getProposal().getProposalVersion());
        assertEquals(AgentApprovalStatusEnum.EXPIRED,
                gateway.getApproval(first.getApproval().getId()).getStatus());
        assertEquals(AgentApprovalStatusEnum.PENDING, second.getApproval().getStatus());
    }

    @Test
    void approvedExternalSqlRequeuesSameRunAfterItsLeaseWasSuspended() {
        runService.get(creation.getInitialRun().getId()).setRuntimeType(AgentRuntimeTypeEnum.EXTERNAL_AGENT);
        AgentRuntimeRunLease suspended = new AgentRuntimeRunLease();
        suspended.setRunId(creation.getInitialRun().getId());
        suspended.setState(AgentRuntimeLeaseStateEnum.SUSPENDED);
        IAgentRuntimeControlStorage runtimeStorage = (IAgentRuntimeControlStorage) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IAgentRuntimeControlStorage.class},
                (proxy, method, args) -> {
                    if ("getRuntimeRunLease".equals(method.getName())) return suspended;
                    throw new UnsupportedOperationException(method.getName());
                });
        gateway.setRuntimeStorage(runtimeStorage);
        AgentSqlExecutionPermit pending = gateway.prepareSql(
                request("external-write", "update refunds set status = 'REVIEW' where id = 1"));

        AgentApprovalDecisionRequest decision = new AgentApprovalDecisionRequest();
        decision.setApprovalId(pending.getApproval().getId());
        decision.setExpectedRevision(1L);
        decision.setDecision(AgentApprovalDecisionEnum.APPROVE);
        decision.setDecidedBy(9L);
        gateway.decide(decision);

        assertEquals(AgentRunStatusEnum.QUEUED,
                runService.get(creation.getInitialRun().getId()).getStatus());
        assertEquals(AgentTaskStatusEnum.IN_PROGRESS,
                taskService.get(creation.getTask().getId()).getStatus());
    }

    private AgentSqlToolRequest request(String callId, String sql) {
        AgentSqlToolRequest request = new AgentSqlToolRequest();
        request.setRunId(creation.getInitialRun().getId());
        request.setToolCallId(callId);
        request.setSql(sql);
        request.setDataSourceId(7L);
        request.setDatabaseName("sales");
        request.setSchemaName("public");
        return request;
    }
}
