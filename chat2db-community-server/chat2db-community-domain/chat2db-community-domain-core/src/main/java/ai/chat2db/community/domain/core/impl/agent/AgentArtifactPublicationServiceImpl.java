package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactContentModeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentArtifactTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentArtifact;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDashboardRef;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactDetail;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactEvidence;
import ai.chat2db.community.domain.api.model.agent.AgentArtifactVersion;
import ai.chat2db.community.domain.api.model.chart.Chart;
import ai.chat2db.community.domain.api.model.chart.Dashboard;
import ai.chat2db.community.domain.api.model.request.agent.AgentArtifactPublishRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactPublicationService;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactService;
import ai.chat2db.community.domain.api.service.dashboard.IDashboardService;
import ai.chat2db.community.domain.api.service.storage.IAgentControlStorage;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.core.impl.ai.AgentToolScopePolicy;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class AgentArtifactPublicationServiceImpl implements IAgentArtifactPublicationService {

    private final IAgentArtifactService artifactService;
    private final IAgentControlStorage storage;
    private final IDashboardService dashboardService;
    private final IWorkspaceStorageFacade workspaceStorage;

    public AgentArtifactPublicationServiceImpl(IAgentArtifactService artifactService,
                                               IAgentControlStorage storage,
                                               IDashboardService dashboardService,
                                               IWorkspaceStorageFacade workspaceStorage) {
        this.artifactService = artifactService;
        this.storage = storage;
        this.dashboardService = dashboardService;
        this.workspaceStorage = workspaceStorage;
    }

    @Override
    public AgentArtifactDashboardRef publishChart(AgentArtifactPublishRequest request) {
        validate(request);
        AgentArtifactDetail detail = artifactService.get(request.getArtifactId());
        AgentArtifact artifact = detail.getArtifact();
        if (artifact.getType() != AgentArtifactTypeEnum.CHART) {
            throw new IllegalArgumentException("only CHART artifacts can be published to Dashboard");
        }
        int versionNumber = request.getArtifactVersion() == null
                ? artifact.getCurrentVersion() : request.getArtifactVersion();
        AgentArtifactVersion version = detail.getVersions().stream()
                .filter(candidate -> Objects.equals(candidate.getVersion(), versionNumber))
                .findFirst().orElseThrow(() -> new NoSuchElementException("artifact version not found"));
        int chartIndex = request.getChartIndex() == null ? 0 : request.getChartIndex();
        Map<String, Object> chartSpec = chartSpec(version, chartIndex);
        Dashboard dashboard = dashboardService.getDashboard(request.getDashboardId());
        if (dashboard == null) {
            throw new NoSuchElementException("dashboard not found: " + request.getDashboardId());
        }
        if (dashboard.getUserId() != null && !dashboard.getUserId().equals(request.getPublishedBy())) {
            throw new IllegalArgumentException("dashboard is not accessible to the current user");
        }
        AgentArtifactContentModeEnum mode = request.getContentMode() == null
                ? AgentArtifactContentModeEnum.SNAPSHOT : request.getContentMode();
        AgentArtifactDashboardRef existing = storage.listArtifactDashboardRefs(artifact.getTaskId()).stream()
                .filter(reference -> reference.getArtifactId().equals(artifact.getId()))
                .filter(reference -> reference.getArtifactVersion().equals(versionNumber))
                .filter(reference -> reference.getChartIndex().equals(chartIndex))
                .filter(reference -> reference.getDashboardId().equals(dashboard.getId()))
                .filter(reference -> reference.getContentMode() == mode)
                .findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }

        List<AgentArtifactEvidence> evidence = detail.getEvidence().stream()
                .filter(item -> Objects.equals(item.getArtifactVersion(), versionNumber)).toList();
        AgentArtifactEvidence primaryEvidence = evidence.stream().findFirst().orElse(null);
        if (mode == AgentArtifactContentModeEnum.LIVE) {
            requireLiveEvidence(primaryEvidence);
        }
        Chart chart = dashboardChart(artifact, versionNumber, chartIndex, chartSpec, primaryEvidence, mode,
                request.getPublishedBy());

        List<Long> originalChartIds = dashboard.getChartIds() == null
                ? new ArrayList<>() : new ArrayList<>(dashboard.getChartIds());
        String originalSchema = dashboard.getSchema();
        Long chartId = dashboardService.createChart(chart);
        try {
            appendChart(dashboard, chartId);
            dashboardService.updateDashboard(dashboard);
            AgentArtifactDashboardRef reference = new AgentArtifactDashboardRef();
            reference.setId(UUID.randomUUID().toString());
            reference.setTaskId(artifact.getTaskId());
            reference.setArtifactId(artifact.getId());
            reference.setArtifactVersion(versionNumber);
            reference.setChartIndex(chartIndex);
            reference.setDashboardId(dashboard.getId());
            reference.setChartId(chartId);
            reference.setContentMode(mode);
            reference.setPublishedBy(request.getPublishedBy());
            reference.setPublishedAt(new Date());
            return storage.createOrGetArtifactDashboardRef(reference);
        } catch (RuntimeException exception) {
            dashboard.setChartIds(originalChartIds);
            dashboard.setSchema(originalSchema);
            try {
                dashboardService.updateDashboard(dashboard);
                dashboardService.deleteChart(chartId);
            } catch (RuntimeException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
    }

    @Override
    public List<AgentArtifactDashboardRef> listByTask(String taskId) {
        if (StringUtils.isBlank(taskId) || storage.getTask(taskId) == null) {
            throw new NoSuchElementException("task not found: " + taskId);
        }
        return storage.listArtifactDashboardRefs(taskId);
    }

    @Override
    public void authorizeRefresh(Long chartId, Long currentUserId) {
        AgentArtifactDashboardRef reference = storage.getArtifactDashboardRefByChartId(chartId);
        if (reference == null) {
            return;
        }
        if (reference.getContentMode() != AgentArtifactContentModeEnum.LIVE) {
            throw new IllegalArgumentException("SNAPSHOT Agent charts cannot be refreshed");
        }
        AgentTask task = storage.getTask(reference.getTaskId());
        if (task == null || !Objects.equals(task.getCreatedBy(), currentUserId)
                || !Objects.equals(reference.getPublishedBy(), currentUserId)) {
            throw new IllegalArgumentException("Agent Dashboard chart is not accessible to the current user");
        }
        AgentArtifactDetail detail = artifactService.get(reference.getArtifactId());
        AgentArtifactEvidence evidence = detail.getEvidence().stream()
                .filter(item -> Objects.equals(item.getArtifactVersion(), reference.getArtifactVersion()))
                .findFirst().orElseThrow(() -> new IllegalStateException("LIVE chart evidence is unavailable"));
        requireLiveEvidence(evidence);
        if (workspaceStorage.queryDataSourceById(evidence.getDataSourceId(), false) == null) {
            throw new IllegalArgumentException("LIVE chart datasource is no longer accessible");
        }
        boolean scoped = (task.getDataScopeSnapshot() == null ? List.<ai.chat2db.community.domain.api.model.agent.AgentDataScope>of()
                : task.getDataScopeSnapshot()).stream().anyMatch(scope -> {
            try {
                AgentToolScopePolicy.requireConnection(scope, evidence.getDataSourceId(),
                        evidence.getDatabaseName(), evidence.getSchemaName());
                AgentToolScopePolicy.requireSql(scope, evidence.getSqlSnapshot());
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        });
        if (!scoped) {
            throw new IllegalArgumentException("LIVE chart SQL is outside the current Task data scope");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chartSpec(AgentArtifactVersion version, int index) {
        Object value = version.getContent().get("charts");
        if (!(value instanceof List<?> charts) || index < 0 || index >= charts.size()
                || !(charts.get(index) instanceof Map<?, ?> chart)) {
            throw new IllegalArgumentException("artifact chart index is invalid");
        }
        return new LinkedHashMap<>((Map<String, Object>) chart);
    }

    private Chart dashboardChart(AgentArtifact artifact, int version, int chartIndex,
                                 Map<String, Object> chartSpec, AgentArtifactEvidence evidence,
                                 AgentArtifactContentModeEnum mode, Long userId) {
        Object dataValue = chartSpec.remove("data");
        List<?> rows = dataValue instanceof List<?> list ? list : List.of();
        Map<String, Object> source = Map.of(
                "sourceTaskId", artifact.getTaskId(),
                "sourceArtifactId", artifact.getId(),
                "sourceVersion", version,
                "sourceChartIndex", chartIndex,
                "contentMode", mode.name());
        chartSpec.put("source", source);

        Chart chart = new Chart();
        chart.setName(chartSpec.get("title") instanceof String title ? title : artifact.getTitle());
        chart.setDescription("Published from Agent Task Artifact " + artifact.getId() + " v" + version);
        chart.setChartSchema(chartSpec);
        chart.setMetaData(snapshotMetaData(rows));
        chart.setUserId(userId);
        if (evidence != null) {
            chart.setDataSourceId(evidence.getDataSourceId());
            chart.setDatabaseName(evidence.getDatabaseName());
            chart.setSchemaName(evidence.getSchemaName());
            Map<String, Object> databaseInfo = new LinkedHashMap<>();
            databaseInfo.put("dataSourceId", evidence.getDataSourceId());
            databaseInfo.put("databaseName", evidence.getDatabaseName());
            databaseInfo.put("schemaName", evidence.getSchemaName());
            if (mode == AgentArtifactContentModeEnum.LIVE) {
                databaseInfo.put("sql", evidence.getSqlSnapshot());
            }
            chart.setDatabaseInfo(databaseInfo);
        }
        return chart;
    }

    private Map<String, Object> snapshotMetaData(List<?> rows) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) map.keySet().stream().map(String::valueOf).forEach(columns::add);
        }
        List<Map<String, Object>> headers = new ArrayList<>();
        for (String column : columns) {
            Object sample = rows.stream().filter(Map.class::isInstance)
                    .map(Map.class::cast).map(row -> row.get(column)).filter(Objects::nonNull)
                    .findFirst().orElse(null);
            headers.add(Map.of("name", column, "dataType", sample instanceof Number ? "numeric" : "string"));
        }
        List<List<Object>> data = rows.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(row -> columns.stream().map(row::get).toList()).toList();
        return Map.of("headerList", headers, "dataList", data);
    }

    private void requireLiveEvidence(AgentArtifactEvidence evidence) {
        if (evidence == null || evidence.getDataSourceId() == null || StringUtils.isBlank(evidence.getSqlSnapshot())) {
            throw new IllegalArgumentException("LIVE publication requires SQL evidence");
        }
        try {
            if (!(CCJSqlParserUtil.parse(evidence.getSqlSnapshot()) instanceof Select)) {
                throw new IllegalArgumentException("LIVE Dashboard publication requires a read-only SELECT");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("LIVE Dashboard SQL cannot be parsed", exception);
        }
    }

    private void appendChart(Dashboard dashboard, Long chartId) {
        List<Long> chartIds = dashboard.getChartIds() == null
                ? new ArrayList<>() : new ArrayList<>(dashboard.getChartIds());
        chartIds.add(chartId);
        dashboard.setChartIds(chartIds);
        List<Map<String, Object>> layout;
        try {
            layout = StringUtils.isBlank(dashboard.getSchema())
                    ? new ArrayList<>()
                    : new ArrayList<>(JSON.parseObject(dashboard.getSchema(),
                    new TypeReference<List<Map<String, Object>>>() { }));
        } catch (RuntimeException exception) {
            layout = new ArrayList<>();
        }
        int maxY = layout.stream().map(item -> item.get("y")).filter(Number.class::isInstance)
                .map(Number.class::cast).mapToInt(Number::intValue).max().orElse(-1);
        layout.add(Map.of("i", String.valueOf(chartId), "x", 0, "y", maxY + 1,
                "w", 6, "h", 4, "minW", 3, "minH", 3, "maxH", 10));
        dashboard.setSchema(JSON.toJSONString(layout));
    }

    private void validate(AgentArtifactPublishRequest request) {
        if (request == null || StringUtils.isBlank(request.getArtifactId())
                || request.getDashboardId() == null || request.getPublishedBy() == null) {
            throw new IllegalArgumentException("artifact, dashboard and publisher are required");
        }
        if (request.getArtifactVersion() != null && request.getArtifactVersion() <= 0) {
            throw new IllegalArgumentException("artifact version must be positive");
        }
        if (request.getChartIndex() != null && request.getChartIndex() < 0) {
            throw new IllegalArgumentException("chart index cannot be negative");
        }
    }
}
