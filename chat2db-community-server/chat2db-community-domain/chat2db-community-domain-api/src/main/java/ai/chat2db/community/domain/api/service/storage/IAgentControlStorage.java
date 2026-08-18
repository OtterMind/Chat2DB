package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactVersion;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDashboardRef;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;

import java.util.List;

public interface IAgentControlStorage {

    AgentDefinition createAgent(AgentDefinition agent);

    AgentDefinition updateAgent(AgentDefinition agent, long expectedRevision);

    AgentDefinition getAgent(String id);

    List<AgentDefinition> listAgents();

    AgentTaskCreation createTaskWithInitialRun(AgentTask task, AgentRun run);

    AgentTaskCreation appendTaskRun(AgentTask task, AgentRun run, long expectedTaskRevision);

    AgentTask getTask(String id);

    List<AgentTask> listTasks();

    List<AgentTask> listArchivedTasks();

    AgentTask updateTask(AgentTask task, long expectedRevision);

    void deleteTask(String taskId, long expectedRevision);

    AgentRun getRun(String id);

    List<AgentRun> listRunsByTask(String taskId);

    AgentRun updateRun(AgentRun run, long expectedRevision);

    AgentRunEvent appendRunEvent(AgentRunEvent event);

    List<AgentRunEvent> listRunEvents(String runId);

    AgentTaskContext appendTaskContext(AgentTaskContext context);

    List<AgentTaskContext> listTaskContexts(String taskId);

    AgentArtifactDetail createArtifact(AgentArtifact artifact, AgentArtifactVersion version,
                                       List<AgentArtifactEvidence> evidence);

    AgentArtifactDetail appendArtifactVersion(AgentArtifact artifact, AgentArtifactVersion version,
                                              List<AgentArtifactEvidence> evidence, long expectedRevision);

    AgentArtifact getArtifact(String id);

    AgentArtifact getArtifactByRunAndType(String taskId, String runId, AgentArtifactTypeEnum type);

    List<AgentArtifact> listArtifactsByTask(String taskId);

    List<AgentArtifactVersion> listArtifactVersions(String artifactId);

    List<AgentArtifactEvidence> listArtifactEvidence(String artifactId);

    AgentSqlProposal createSqlProposal(AgentSqlProposal proposal, AgentApproval approval);

    AgentSqlProposal getSqlProposal(String id);

    AgentSqlProposal findSqlProposal(String runId, String sqlHash, Long dataSourceId,
                                     String databaseName, String schemaName);

    List<AgentSqlProposal> listSqlProposals(String runId);

    AgentSqlProposal updateSqlProposal(AgentSqlProposal proposal, long expectedRevision);

    AgentApproval getApproval(String id);

    AgentApproval findApprovalByProposal(String proposalId);

    List<AgentApproval> listApprovals(String runId);

    AgentApproval updateApproval(AgentApproval approval, long expectedRevision);

    AgentToolAttempt createOrGetToolAttempt(AgentToolAttempt attempt);

    AgentToolAttempt getToolAttempt(String id);

    List<AgentToolAttempt> listToolAttempts(String runId);

    AgentToolAttempt updateToolAttempt(AgentToolAttempt attempt, long expectedRevision);

    AgentArtifactDashboardRef createOrGetArtifactDashboardRef(AgentArtifactDashboardRef reference);

    List<AgentArtifactDashboardRef> listArtifactDashboardRefs(String taskId);

    AgentArtifactDashboardRef getArtifactDashboardRefByChartId(Long chartId);
}
