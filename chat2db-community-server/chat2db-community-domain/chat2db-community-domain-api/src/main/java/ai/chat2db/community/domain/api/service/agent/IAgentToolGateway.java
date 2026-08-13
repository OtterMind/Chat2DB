package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentSqlExecutionPermit;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.request.agent.AgentApprovalDecisionRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentSqlToolRequest;

import java.util.List;

public interface IAgentToolGateway {
    AgentSqlExecutionPermit prepareSql(AgentSqlToolRequest request);
    AgentToolAttempt markSucceeded(String attemptId, String resultContent);
    AgentToolAttempt markFailed(String attemptId, String errorMessage, boolean outcomeUnknown);
    AgentApproval decide(AgentApprovalDecisionRequest request);
    AgentApproval getApproval(String approvalId);
    List<AgentApproval> listApprovals(String runId);
    List<AgentSqlProposal> listProposals(String runId);
    List<AgentToolAttempt> listAttempts(String runId);
}
