package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDashboardRef;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.chart.Chart;
import ai.chat2db.community.domain.api.model.chart.Dashboard;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactPublishRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.service.dashboard.IDashboardService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentArtifactPublicationServiceTest {

    private AgentControlServiceTest.MemoryAgentControlStorage storage;
    private AgentArtifactServiceImpl artifactService;
    private AgentArtifactPublicationServiceImpl publicationService;
    private AgentTaskCreation creation;
    private Dashboard dashboard;
    private Map<Long, Chart> charts;

    @BeforeEach
    void setUp() {
        storage = new AgentControlServiceTest.MemoryAgentControlStorage();
        AgentDefinitionServiceImpl agentService = new AgentDefinitionServiceImpl(storage);
        AgentTaskServiceImpl taskService = new AgentTaskServiceImpl(storage);
        artifactService = new AgentArtifactServiceImpl(storage);
        AgentDataScope scope = new AgentDataScope();
        scope.setDataSourceId(7L);
        scope.setDatabaseName("sales");
        scope.setSchemaName("public");
        AgentDefinitionCreateRequest agentRequest = new AgentDefinitionCreateRequest();
        agentRequest.setName("Publisher");
        agentRequest.setDataScopes(List.of(scope));
        AgentDefinition agent = agentService.create(agentRequest);
        AgentTaskCreateRequest taskRequest = new AgentTaskCreateRequest();
        taskRequest.setTitle("Revenue trend");
        taskRequest.setAssigneeAgentId(agent.getId());
        taskRequest.setCreatedBy(7L);
        taskRequest.setDataScopeSnapshot(List.of(scope));
        creation = taskService.create(taskRequest);

        dashboard = new Dashboard();
        dashboard.setId(10L);
        dashboard.setName("Executive dashboard");
        dashboard.setUserId(7L);
        dashboard.setChartIds(new ArrayList<>());
        charts = new LinkedHashMap<>();
        AtomicLong sequence = new AtomicLong(100L);
        IDashboardService dashboardService = (IDashboardService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDashboardService.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getDashboard" -> dashboard;
                        case "createChart" -> {
                            long id = sequence.incrementAndGet();
                            charts.put(id, (Chart) args[0]);
                            yield id;
                        }
                        case "updateDashboard" -> null;
                        case "deleteChart" -> charts.remove((Long) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        IWorkspaceStorageFacade workspaceStorage = (IWorkspaceStorageFacade) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IWorkspaceStorageFacade.class}, (proxy, method, args) -> {
                    if ("queryDataSourceById".equals(method.getName())) {
                        WorkspaceDataSource dataSource = new WorkspaceDataSource();
                        dataSource.setId((Long) args[0]);
                        return dataSource;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        publicationService = new AgentArtifactPublicationServiceImpl(
                artifactService, storage, dashboardService, workspaceStorage);
    }

    @Test
    void publishesSnapshotCopyWithSourceReferenceIdempotently() {
        String artifactId = chartArtifact("select month, revenue from orders");
        AgentArtifactPublishRequest request = publishRequest(artifactId, AgentArtifactContentModeEnum.SNAPSHOT);

        AgentArtifactDashboardRef first = publicationService.publishChart(request);
        AgentArtifactDashboardRef duplicate = publicationService.publishChart(request);

        assertEquals(first.getId(), duplicate.getId());
        assertEquals(List.of(first.getChartId()), dashboard.getChartIds());
        Chart chart = charts.get(first.getChartId());
        Map<?, ?> source = (Map<?, ?>) ((Map<?, ?>) chart.getChartSchema()).get("source");
        assertEquals(artifactId, source.get("sourceArtifactId"));
        assertFalse(((Map<?, ?>) chart.getDatabaseInfo()).containsKey("sql"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) chart.getMetaData()).get("dataList")).size());
    }

    @Test
    void livePublicationKeepsReadOnlySqlAndRejectsWriteEvidence() {
        String selectArtifact = chartArtifact("select month, revenue from orders");
        AgentArtifactDashboardRef published = publicationService.publishChart(
                publishRequest(selectArtifact, AgentArtifactContentModeEnum.LIVE));
        assertEquals("select month, revenue from orders",
                ((Map<?, ?>) charts.get(published.getChartId()).getDatabaseInfo()).get("sql"));
        publicationService.authorizeRefresh(published.getChartId(), 7L);
        assertThrows(IllegalArgumentException.class,
                () -> publicationService.authorizeRefresh(published.getChartId(), 8L));

        String writeArtifact = chartArtifact("delete from orders");
        assertThrows(IllegalArgumentException.class, () -> publicationService.publishChart(
                publishRequest(writeArtifact, AgentArtifactContentModeEnum.LIVE)));
        assertTrue(publicationService.listByTask(creation.getTask().getId()).stream()
                .allMatch(reference -> reference.getContentMode() == AgentArtifactContentModeEnum.LIVE));
    }

    private String chartArtifact(String sql) {
        AgentArtifactEvidence evidence = new AgentArtifactEvidence();
        evidence.setRunId(creation.getInitialRun().getId());
        evidence.setDataSourceId(7L);
        evidence.setDatabaseName("sales");
        evidence.setSchemaName("public");
        evidence.setSqlSnapshot(sql);
        AgentArtifactCreateRequest request = new AgentArtifactCreateRequest();
        request.setTaskId(creation.getTask().getId());
        request.setType(AgentArtifactTypeEnum.CHART);
        request.setTitle("Revenue chart " + sql.hashCode());
        request.setStatus(AgentArtifactStatusEnum.READY);
        request.setCreatedByRunId(null);
        request.setContent(Map.of("charts", List.of(Map.of(
                "chartType", "Line", "xField", "month", "yField", "revenue", "title", "Revenue",
                "data", List.of(Map.of("month", "Jan", "revenue", 10),
                        Map.of("month", "Feb", "revenue", 12))))));
        request.setEvidence(List.of(evidence));
        return artifactService.create(request).getArtifact().getId();
    }

    private AgentArtifactPublishRequest publishRequest(String artifactId, AgentArtifactContentModeEnum mode) {
        AgentArtifactPublishRequest request = new AgentArtifactPublishRequest();
        request.setArtifactId(artifactId);
        request.setArtifactVersion(1);
        request.setChartIndex(0);
        request.setDashboardId(dashboard.getId());
        request.setContentMode(mode);
        request.setPublishedBy(7L);
        return request;
    }
}
