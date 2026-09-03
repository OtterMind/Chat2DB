package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.chart.Chart;
import ai.chat2db.community.domain.api.model.chart.Dashboard;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsSqlOperationLogListResultRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.service.dashboard.IDashboardService;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.storage.small.ChartStorage;
import ai.chat2db.community.storage.small.DashboardStorage;
import ai.chat2db.community.tools.annotation.LocalPersistenceRuntimeOnly;
import ai.chat2db.community.tools.exception.BusinessException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@LocalPersistenceRuntimeOnly
public class LocalDashboardService implements IDashboardService {

    private static final int DEFAULT_CHART_REFRESH_PAGE_SIZE = 200;

    private final IDbConnectionContextService connectionContextService;
    private final IDbDlTemplateService dlTemplateService;
    private final IOpsSqlOperationLogService sqlOperationLogService;

    public LocalDashboardService(IDbConnectionContextService connectionContextService,
            IDbDlTemplateService dlTemplateService,
            IOpsSqlOperationLogService sqlOperationLogService) {
        this.connectionContextService = connectionContextService;
        this.dlTemplateService = dlTemplateService;
        this.sqlOperationLogService = sqlOperationLogService;
    }

    @Override
    public PageResponse<Dashboard> listDashboards(Integer pageNo, Integer pageSize, String searchKey) {
        int normalizedPageNo = Math.max(1, pageNo == null ? 1 : pageNo);
        int normalizedPageSize = Math.max(1, pageSize == null ? 20 : pageSize);
        List<Dashboard> dashboards = filterDashboards(DashboardStorage.INSTANCE.getDataList(), searchKey);
        long total = dashboards.size();
        long offset = ((long) normalizedPageNo - 1L) * normalizedPageSize;
        if (offset >= total) {
            return PageResponse.of(List.of(), total, normalizedPageNo, normalizedPageSize);
        }
        int fromIndex = (int) offset;
        int toIndex = (int) Math.min(offset + (long) normalizedPageSize, total);
        return PageResponse.of(dashboards.subList(fromIndex, toIndex), total,
                normalizedPageNo, normalizedPageSize);
    }

    @Override
    public Dashboard getDashboard(Long id) {
        return DashboardStorage.INSTANCE.getById(id);
    }

    @Override
    public Long createDashboard(Dashboard dashboard) {
        Date now = new Date();
        dashboard.setGmtCreate(now);
        dashboard.setGmtModified(now);
        if (dashboard.getChartIds() == null) {
            dashboard.setChartIds(new ArrayList<>());
        }
        return DashboardStorage.INSTANCE.save(dashboard);
    }

    @Override
    public void updateDashboard(Dashboard dashboard) {
        dashboard.setGmtModified(new Date());
        DashboardStorage.INSTANCE.update(dashboard);
    }

    @Override
    public void deleteDashboard(Long id) {
        Dashboard dashboard = DashboardStorage.INSTANCE.getById(id);
        if (dashboard != null && dashboard.getChartIds() != null) {
            dashboard.getChartIds().forEach(ChartStorage.INSTANCE::delete);
        }
        DashboardStorage.INSTANCE.delete(id);
    }

    @Override
    public Chart getChart(Long id) {
        return ChartStorage.INSTANCE.getById(id);
    }

    @Override
    public Chart getChartDetail(Long chartId, Boolean refresh) {
        Chart chart = ChartStorage.INSTANCE.getById(chartId);
        if (chart == null) {
            return null;
        }
        Chart response = JSON.parseObject(JSON.toJSONString(chart), Chart.class);
        if (Boolean.TRUE.equals(refresh)) {
            refreshChartMetaData(response);
        }
        return response;
    }

    @Override
    public Long createChart(Chart chart) {
        Date now = new Date();
        chart.setGmtCreate(now);
        chart.setGmtModified(now);
        if (StringUtils.isBlank(chart.getName())) {
            chart.setName(resolveChartTitle(chart));
        }
        return ChartStorage.INSTANCE.save(chart);
    }

    @Override
    public void updateChart(Chart chart) {
        chart.setGmtModified(new Date());
        if (StringUtils.isBlank(chart.getName())) {
            chart.setName(resolveChartTitle(chart));
        }
        ChartStorage.INSTANCE.update(chart);
    }

    @Override
    public void deleteChart(Long id) {
        ChartStorage.INSTANCE.delete(id);
    }

    private List<Dashboard> filterDashboards(List<Dashboard> dashboards, String searchKey) {
        if (StringUtils.isBlank(searchKey)) {
            return dashboards;
        }
        String normalizedSearchKey = searchKey.toLowerCase(Locale.ROOT);
        return dashboards.stream()
                .filter(dashboard -> contains(dashboard.getName(), normalizedSearchKey)
                        || contains(dashboard.getDescription(), normalizedSearchKey))
                .toList();
    }

    private boolean contains(String text, String normalizedSearchKey) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(normalizedSearchKey);
    }

    private void refreshChartMetaData(Chart chart) {
        JSONObject databaseInfo = toJsonObject(chart.getDatabaseInfo());
        Long dataSourceId = longValue(databaseInfo, "dataSourceId");
        String sql = databaseInfo.getString("sql");
        if (dataSourceId == null || StringUtils.isBlank(sql)) {
            return;
        }

        Long consoleId = Objects.requireNonNullElse(longValue(databaseInfo, "consoleId"), System.currentTimeMillis());
        String databaseName = databaseInfo.getString("databaseName");
        String schemaName = databaseInfo.getString("schemaName");

        DbConnectionContextRequest contextRequest = new DbConnectionContextRequest();
        contextRequest.setDataSourceId(dataSourceId);
        contextRequest.setConsoleId(consoleId);
        contextRequest.setDatabaseName(databaseName);
        contextRequest.setSchemaName(schemaName);

        DbDlExecuteRequest executeRequest = new DbDlExecuteRequest();
        executeRequest.setSql(sql);
        executeRequest.setDataSourceId(dataSourceId);
        executeRequest.setConsoleId(consoleId);
        executeRequest.setDatabaseName(databaseName);
        executeRequest.setSchemaName(schemaName);
        executeRequest.setPageNo(1);
        executeRequest.setPageSize(DEFAULT_CHART_REFRESH_PAGE_SIZE);
        executeRequest.setPageSizeAll(false);
        executeRequest.setSingle(true);
        executeRequest.setErrorContinue(false);

        try {
            connectionContextService.bind(contextRequest);
            List<ExecuteResponse> results = dlTemplateService.execute(executeRequest);
            sqlOperationLogService.recordListResultAsync(OpsSqlOperationLogListResultRequest.of(
                    sql, executeSuccess(results), executeErrorMessage(results), results,
                    SqlOperationLogSourceEnum.CHART.name()));
            attachMetaData(chart, results);
        } catch (RuntimeException e) {
            sqlOperationLogService.recordFailureAsync(sql, SqlOperationLogSourceEnum.CHART.name(), e.getMessage());
            throw e;
        } finally {
            connectionContextService.clear();
        }
    }

    private void attachMetaData(Chart chart, List<ExecuteResponse> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        ExecuteResponse firstResult = results.get(0);
        if (firstResult == null) {
            return;
        }
        if (!Boolean.TRUE.equals(firstResult.getSuccess())) {
            throw new BusinessException(firstResult.getMessage());
        }
        Map<String, Object> metaData = new LinkedHashMap<>();
        metaData.put("dataList", firstResult.getDisplayDataList());
        metaData.put("headerList", firstResult.getHeaderList());
        chart.setMetaData(metaData);
    }

    private Boolean executeSuccess(List<ExecuteResponse> results) {
        return results != null && results.stream()
                .allMatch(result -> result != null && Boolean.TRUE.equals(result.getSuccess()));
    }

    private String executeErrorMessage(List<ExecuteResponse> results) {
        if (results == null) {
            return null;
        }
        return results.stream()
                .filter(result -> result != null && !Boolean.TRUE.equals(result.getSuccess()))
                .map(ExecuteResponse::getMessage)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private String resolveChartTitle(Chart chart) {
        JSONObject chartSchema = toJsonObject(chart.getChartSchema());
        String title = chartSchema.getString("title");
        if (StringUtils.isBlank(title)) {
            title = chartSchema.getString("summary");
        }
        return title;
    }

    private JSONObject toJsonObject(Object value) {
        if (value == null) {
            return new JSONObject();
        }
        if (value instanceof JSONObject jsonObject) {
            return jsonObject;
        }
        return JSON.parseObject(JSON.toJSONString(value));
    }

    private Long longValue(JSONObject jsonObject, String key) {
        Object value = jsonObject.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
