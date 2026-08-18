package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.chart.Chart;
import ai.chat2db.community.domain.api.model.chart.Dashboard;
import ai.chat2db.community.domain.api.service.dashboard.IDashboardService;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactPublicationService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.annotation.NotCliRuntime;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.model.request.dashboard.ChartDetailRequest;
import ai.chat2db.community.web.api.model.request.dashboard.DashboardListRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@NotCliRuntime
public class DbDashboardController {

    private final IDashboardService dashboardService;
    private final IAgentArtifactPublicationService agentPublicationService;
    private final IIdentityService identityService;

    public DbDashboardController(IDashboardService dashboardService,
                                 IAgentArtifactPublicationService agentPublicationService,
                                 IIdentityService identityService) {
        this.dashboardService = dashboardService;
        this.agentPublicationService = agentPublicationService;
        this.identityService = identityService;
    }

    @GetMapping("/dashboard/list")
    public WebPageResult<Dashboard> listDashboards(DashboardListRequest request) {
        PageResponse<Dashboard> pageResult = dashboardService.listDashboards(request.getPageNoOrDefault(),
                request.getPageSizeOrDefault(), request.getSearchKey());
        return WebPageResult.of(pageResult.getData(), pageResult.getTotal(), pageResult.getPageNo(),
                pageResult.getPageSize());
    }

    @GetMapping("/dashboard")
    public DataResult<Dashboard> getDashboard(@RequestParam("id") Long id) {
        return DataResult.of(dashboardService.getDashboard(id));
    }

    @PostMapping("/dashboard/create")
    public DataResult<Long> createDashboard(@Valid @RequestBody Dashboard dashboard) {
        return DataResult.of(dashboardService.createDashboard(dashboard));
    }

    @PostMapping("/dashboard/update")
    public ActionResult updateDashboard(@Valid @RequestBody Dashboard dashboard) {
        dashboardService.updateDashboard(dashboard);
        return ActionResult.isSuccess();
    }

    @DeleteMapping("/dashboard")
    public DataResult<String> deleteDashboard(@RequestParam("id") Long id) {
        dashboardService.deleteDashboard(id);
        return DataResult.of("success");
    }

    @GetMapping("/v1/chart")
    public DataResult<Chart> getChart(@RequestParam("id") Long id) {
        return DataResult.of(dashboardService.getChart(id));
    }

    @GetMapping("/chart/detail")
    public DataResult<Chart> getChartDetail(ChartDetailRequest request) {
        if (Boolean.TRUE.equals(request.getRefresh())) {
            agentPublicationService.authorizeRefresh(request.getChartId(), identityService.currentUserId());
        }
        return DataResult.of(dashboardService.getChartDetail(request.getChartId(),
                Boolean.TRUE.equals(request.getRefresh())));
    }

    @PostMapping("/v1/chart/create")
    public DataResult<Long> createChart(@Valid @RequestBody Chart chart) {
        return DataResult.of(dashboardService.createChart(chart));
    }

    @PostMapping("/v1/chart/update")
    public ActionResult updateChart(@Valid @RequestBody Chart chart) {
        dashboardService.updateChart(chart);
        return ActionResult.isSuccess();
    }

    @DeleteMapping("/chart")
    public DataResult<String> deleteChart(@RequestParam("id") Long id) {
        dashboardService.deleteChart(id);
        return DataResult.of("success");
    }
}
