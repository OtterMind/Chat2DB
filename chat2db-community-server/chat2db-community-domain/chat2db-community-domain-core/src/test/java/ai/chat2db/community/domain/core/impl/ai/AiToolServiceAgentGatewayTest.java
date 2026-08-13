package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.enums.agent.AgentRiskLevelEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlPermitDecisionEnum;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentSqlExecutionPermit;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.request.agent.AgentSqlToolRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.service.agent.IAgentToolGateway;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolServiceAgentGatewayTest {

    @Test
    void pausesAgentSqlBeforeDatabaseExecutionWhenApprovalIsRequired() throws Exception {
        AtomicReference<AgentSqlToolRequest> captured = new AtomicReference<>();
        AgentSqlExecutionPermit permit = new AgentSqlExecutionPermit();
        permit.setDecision(AgentSqlPermitDecisionEnum.APPROVAL_REQUIRED);
        AgentApproval approval = new AgentApproval();
        approval.setId("approval-1");
        permit.setApproval(approval);
        AgentSqlProposal proposal = new AgentSqlProposal();
        proposal.setProposalVersion(3);
        proposal.setRiskLevel(AgentRiskLevelEnum.HIGH);
        permit.setProposal(proposal);

        AiToolServiceImpl service = service(request -> {
            captured.set(request);
            return permit;
        });
        String result = service.executeSql(request("run-1", "UPDATE orders SET status='DONE'", 7L));

        assertTrue(result.contains("approvalId=approval-1"));
        assertTrue(result.contains("proposalVersion=3"));
        assertEquals("run-1", captured.get().getRunId());
        assertEquals(7L, captured.get().getDataSourceId());
    }

    @Test
    void replaysPersistedAttemptResultWithoutOpeningDatabaseConnection() throws Exception {
        AgentSqlExecutionPermit permit = new AgentSqlExecutionPermit();
        permit.setDecision(AgentSqlPermitDecisionEnum.REPLAY_RESULT);
        permit.setReplayResult("persisted rows");
        AiToolServiceImpl service = service(request -> permit);

        assertEquals("persisted rows", service.executeSql(request("run-1", "SELECT * FROM orders", 7L)));
    }

    private AiToolServiceImpl service(SqlPrepare prepare) throws Exception {
        AiToolServiceImpl service = new AiToolServiceImpl();
        IAgentToolGateway gateway = (IAgentToolGateway) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IAgentToolGateway.class},
                (proxy, method, args) -> {
                    if ("prepareSql".equals(method.getName())) {
                        return prepare.apply((AgentSqlToolRequest) args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IDbConnectionContextService connectionService = (IDbConnectionContextService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDbConnectionContextService.class},
                (proxy, method, args) -> {
                    if ("buildProfile".equals(method.getName())) {
                        DbConnectionContextRequest request = (DbConnectionContextRequest) args[0];
                        ConnectionProfile profile = new ConnectionProfile();
                        profile.setDataSourceId(request.getDataSourceId());
                        profile.setDatabaseName(request.getDatabaseName());
                        profile.setSchemaName(request.getSchemaName());
                        return profile;
                    }
                    throw new AssertionError("database connection must not be used: " + method.getName());
                });
        set(service, "agentToolGateway", gateway);
        set(service, "connectionContextService", connectionService);
        return service;
    }

    private AiExecuteSqlRequest request(String runId, String sql, Long dataSourceId) {
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(dataSourceId);
        scope.setMaxRows(100);
        AiToolContextRequest context = new AiToolContextRequest();
        context.setAgentRunId(runId);
        context.setAgentDataScope(scope);
        AiExecuteSqlRequest request = new AiExecuteSqlRequest();
        request.setSql(sql);
        request.setDataSourceId(dataSourceId);
        request.setAiToolContextRequest(context);
        return request;
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @FunctionalInterface
    private interface SqlPrepare {
        AgentSqlExecutionPermit apply(AgentSqlToolRequest request);
    }
}
