package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.chart.Chart;
import ai.chat2db.community.domain.api.service.agent.IAgentArtifactPublicationService;
import ai.chat2db.community.domain.api.service.dashboard.IDashboardService;
import ai.chat2db.community.web.api.model.request.dashboard.ChartDetailRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDashboardControllerAgentAuthorizationTest {

    @Test
    void authorizesAgentLiveChartBeforeDashboardRefresh() {
        AtomicBoolean authorized = new AtomicBoolean();
        IAgentArtifactPublicationService publicationService = (IAgentArtifactPublicationService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IAgentArtifactPublicationService.class},
                (proxy, method, args) -> {
                    if ("authorizeRefresh".equals(method.getName())) {
                        authorized.set(true);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        IDashboardService dashboardService = (IDashboardService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDashboardService.class},
                (proxy, method, args) -> {
                    if ("getChartDetail".equals(method.getName())) {
                        assertTrue(authorized.get());
                        return new Chart();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DbDashboardController controller = new DbDashboardController(
                dashboardService, publicationService, () -> 7L);
        ChartDetailRequest request = new ChartDetailRequest();
        request.setChartId(100L);
        request.setRefresh(true);

        controller.getChartDetail(request);

        assertTrue(authorized.get());
    }
}
