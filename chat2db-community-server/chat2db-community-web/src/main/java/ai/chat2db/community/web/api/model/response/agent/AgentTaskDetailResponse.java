package ai.chat2db.community.web.api.model.response.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentApproval;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDashboardRef;
import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.agent.AgentConnectorAuditContext;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentTaskDetailResponse {

    private AgentTask task;

    private boolean connectorAudit;

    private AgentConnectorAuditContext connectorContext;

    private List<AgentRun> runs = new ArrayList<>();

    private Map<String, List<AgentRunEvent>> eventsByRunId = new LinkedHashMap<>();

    private List<AgentArtifactDetail> artifacts = new ArrayList<>();

    private List<AgentSqlProposal> sqlProposals = new ArrayList<>();

    private List<AgentApproval> approvals = new ArrayList<>();

    private List<AgentToolAttempt> toolAttempts = new ArrayList<>();

    private List<AgentArtifactDashboardRef> dashboardPublications = new ArrayList<>();

    private List<AgentTaskContext> contexts = new ArrayList<>();
}
