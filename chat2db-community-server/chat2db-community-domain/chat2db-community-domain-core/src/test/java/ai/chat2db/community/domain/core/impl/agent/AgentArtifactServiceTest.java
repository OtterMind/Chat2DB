package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentSqlProposal;
import ai.chat2db.community.domain.api.model.agent.AgentToolAttempt;
import ai.chat2db.community.domain.api.enums.agent.AgentToolAttemptStatusEnum;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactVersionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentArtifactServiceTest {

    private AgentControlServiceTest.MemoryAgentControlStorage storage;
    private AgentArtifactServiceImpl artifactService;
    private AgentDefinition agent;
    private AgentTaskCreation creation;

    @BeforeEach
    void setUp() {
        storage = new AgentControlServiceTest.MemoryAgentControlStorage();
        AgentDefinitionServiceImpl agentService = new AgentDefinitionServiceImpl(storage);
        AgentTaskServiceImpl taskService = new AgentTaskServiceImpl(storage);
        artifactService = new AgentArtifactServiceImpl(storage);

        AgentDefinitionCreateRequest agentRequest = new AgentDefinitionCreateRequest();
        agentRequest.setName("Analyst");
        agent = agentService.create(agentRequest);
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Analyze refunds");
        taskRequest.setAssigneeAgentId(agent.getId());
        creation = taskService.create(taskRequest);
    }

    @Test
    void createsIdempotentReportWithImmutableVersionAndEvidence() {
        AgentArtifactEvidence evidence = new AgentArtifactEvidence();
        evidence.setRunId(creation.getInitialRun().getId());
        evidence.setDataSourceId(7L);
        evidence.setSqlSnapshot("select count(*) from refunds");
        evidence.setRowCount(1L);
        AgentArtifactCreateRequest request = reportRequest(evidence);

        AgentArtifactDetail created = artifactService.create(request);
        AgentArtifactDetail duplicate = artifactService.create(request);

        assertEquals(created.getArtifact().getId(), duplicate.getArtifact().getId());
        assertEquals(1, created.getArtifact().getCurrentVersion());
        assertEquals("SUMMARY", ((Map<?, ?>) ((List<?>) created.getVersions().get(0)
                .getContent().get("blocks")).get(0)).get("type"));
        assertEquals(64, created.getEvidence().get(0).getSqlHash().length());

        AgentArtifactVersionCreateRequest revision = new AgentArtifactVersionCreateRequest();
        revision.setArtifactId(created.getArtifact().getId());
        revision.setExpectedRevision(1L);
        revision.setCreatedByRunId(creation.getInitialRun().getId());
        revision.setContent(reportContent("Updated conclusion"));
        AgentArtifactDetail updated = artifactService.addVersion(revision);

        assertEquals(2, updated.getArtifact().getCurrentVersion());
        assertEquals(2, updated.getVersions().size());
        assertEquals(1, updated.getVersions().get(1).getSupersedesVersion());
        assertNotEquals(updated.getVersions().get(0).getContentHash(),
                updated.getVersions().get(1).getContentHash());
    }

    @Test
    void retainsHistoricalEvidenceButMarksItInvalidAfterTaskScopeChanges() {
        AgentDataScope allowed = new AgentDataScope();
        allowed.setDataSourceId(7L);
        creation.getTask().setDataScopeSnapshot(List.of(allowed));
        AgentArtifactEvidence evidence = new AgentArtifactEvidence();
        evidence.setRunId(creation.getInitialRun().getId());
        evidence.setDataSourceId(7L);
        evidence.setSqlSnapshot("select 1");
        AgentArtifactDetail created = artifactService.create(reportRequest(evidence));

        assertTrue(artifactService.get(created.getArtifact().getId()).getEvidence().get(0).getValid());

        AgentDataScope revoked = new AgentDataScope();
        revoked.setDataSourceId(8L);
        creation.getTask().setDataScopeSnapshot(List.of(revoked));
        AgentArtifactEvidence invalid = artifactService.get(created.getArtifact().getId()).getEvidence().get(0);
        assertFalse(invalid.getValid());
        assertFalse(invalid.getInvalidReason().isBlank());
    }

    @Test
    void enforcesRequiredArtifactAndReportSectionContract() {
        agent.setOutputContract("""
                {"requiredArtifacts":[{"type":"REPORT","min":1}],
                 "requiredSections":["summary","recommendations"]}
                """);
        artifactService.create(reportRequest(null));

        assertFalse(artifactService.satisfiesOutputContract(agent, creation.getTask().getId()));

        AgentArtifactCreateRequest complete = new AgentArtifactCreateRequest();
        complete.setTaskId(creation.getTask().getId());
        complete.setType(AgentArtifactTypeEnum.REPORT);
        complete.setStatus(AgentArtifactStatusEnum.READY);
        complete.setContent(Map.of("blocks", List.of(
                Map.of("type", "SUMMARY", "content", "Summary"),
                Map.of("type", "RECOMMENDATIONS", "items", List.of("Investigate channel A")))));
        complete.setCreatedByRunId(null);
        artifactService.create(complete);

        assertTrue(artifactService.satisfiesOutputContract(agent, creation.getTask().getId()));
    }

    @Test
    void extractsGroundedChartAndDataTableArtifactsFromFinalMarkdown() {
        String runId = creation.getInitialRun().getId();
        AgentSqlProposal proposal = new AgentSqlProposal();
        proposal.setId("proposal-1");
        proposal.setRunId(runId);
        proposal.setProposalVersion(1);
        proposal.setSqlSnapshot("select month, refund_rate from refunds");
        proposal.setSqlHash("sql-hash");
        proposal.setDataSourceId(7L);
        proposal.setRevision(1L);
        storage.createSqlProposal(proposal, null);
        AgentToolAttempt attempt = new AgentToolAttempt();
        attempt.setId("attempt-1");
        attempt.setRunId(runId);
        attempt.setProposalId(proposal.getId());
        attempt.setProposalVersion(1);
        attempt.setToolCallId("call-1");
        attempt.setStatus(AgentToolAttemptStatusEnum.SUCCEEDED);
        attempt.setCompletedAt(new Date());
        attempt.setRevision(1L);
        storage.createOrGetToolAttempt(attempt);

        List<AgentArtifactDetail> extracted = artifactService.extractStructuredArtifacts(
                creation.getTask().getId(), runId, 7L, """
                        Refund rate increased.

                        ```chart
                        {"chartType":"Line","xField":"month","yField":"refund_rate","title":"Refund rate",
                         "data":[{"month":"Jan","refund_rate":1.2},{"month":"Feb","refund_rate":2.1}]}
                        ```
                        """);

        assertEquals(List.of(AgentArtifactTypeEnum.CHART, AgentArtifactTypeEnum.DATA_TABLE), extracted.stream()
                .map(detail -> detail.getArtifact().getType()).toList());
        assertEquals("attempt-1", extracted.get(0).getEvidence().get(0).getToolAttemptId());
        assertEquals(List.of("month", "refund_rate"), ((List<?>) ((Map<?, ?>) ((List<?>) extracted.get(1)
                .getVersions().get(0).getContent().get("tables")).get(0)).get("columns")));
    }

    @Test
    void convertsMermaidPieAndXyChartsToDashboardArtifacts() {
        String runId = creation.getInitialRun().getId();
        AgentSqlProposal proposal = new AgentSqlProposal();
        proposal.setId("proposal-mermaid");
        proposal.setRunId(runId);
        proposal.setProposalVersion(1);
        proposal.setSqlSnapshot("select type, count(*) from channels group by type");
        proposal.setSqlHash("sql-hash");
        proposal.setDataSourceId(7L);
        proposal.setRevision(1L);
        storage.createSqlProposal(proposal, null);
        AgentToolAttempt attempt = new AgentToolAttempt();
        attempt.setId("attempt-mermaid");
        attempt.setRunId(runId);
        attempt.setProposalId(proposal.getId());
        attempt.setProposalVersion(1);
        attempt.setToolCallId("call-mermaid");
        attempt.setStatus(AgentToolAttemptStatusEnum.SUCCEEDED);
        attempt.setCompletedAt(new Date());
        attempt.setRevision(1L);
        storage.createOrGetToolAttempt(attempt);

        List<AgentArtifactDetail> extracted = artifactService.extractStructuredArtifacts(
                creation.getTask().getId(), runId, 7L, """
                        图表展示
                        pie title OneAPI 渠道类型分布（共6个）
                            "智谱 GLM (type=14)" : 5
                            "MiniMax (type=27)" : 1
                        xychart-beta
                            title "各渠道配置情况"
                            x-axis [GLM-PRO, MiniMax, GLM-MAX-3]
                            y-axis "渠道" 0 --> 6
                            bar [1, 1, 1]
                        """);

        assertEquals(List.of(AgentArtifactTypeEnum.CHART, AgentArtifactTypeEnum.DATA_TABLE), extracted.stream()
                .map(detail -> detail.getArtifact().getType()).toList());
        List<?> charts = (List<?>) extracted.get(0).getVersions().get(0).getContent().get("charts");
        assertEquals(2, charts.size());
        assertEquals("Pie", ((Map<?, ?>) charts.get(0)).get("chartType"));
        assertEquals("Column", ((Map<?, ?>) charts.get(1)).get("chartType"));
        assertEquals("GLM-MAX-3", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) charts.get(1)).get("data")).get(2))
                .get("category"));
    }

    private AgentArtifactCreateRequest reportRequest(AgentArtifactEvidence evidence) {
        AgentArtifactCreateRequest request = new AgentArtifactCreateRequest();
        request.setTaskId(creation.getTask().getId());
        request.setType(AgentArtifactTypeEnum.REPORT);
        request.setStatus(AgentArtifactStatusEnum.READY);
        request.setContent(reportContent("Refund rate increased"));
        request.setCreatedByRunId(creation.getInitialRun().getId());
        request.setEvidence(evidence == null ? List.of() : List.of(evidence));
        return request;
    }

    private Map<String, Object> reportContent(String summary) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("artifactType", "REPORT");
        content.put("blocks", List.of(Map.of("type", "SUMMARY", "content", summary)));
        return content;
    }
}
