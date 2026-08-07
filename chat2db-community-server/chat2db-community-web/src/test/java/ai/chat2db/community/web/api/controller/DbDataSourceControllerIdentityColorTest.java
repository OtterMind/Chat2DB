package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.config.Environment;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.converter.data.source.DataSourceWebConverter;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceIdentityColorUpdateRequest;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceIdentityColorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbDataSourceControllerIdentityColorTest {

    @Test
    void exposesLightweightIdentityColorEndpointAndReturnsIdentityPatch() throws Exception {
        AtomicReference<Long> capturedId = new AtomicReference<>();
        AtomicReference<String> capturedColor = new AtomicReference<>();
        IDbWorkspaceDataSourceService service = (IDbWorkspaceDataSourceService) Proxy.newProxyInstance(
                IDbWorkspaceDataSourceService.class.getClassLoader(),
                new Class<?>[]{IDbWorkspaceDataSourceService.class},
                (proxy, method, args) -> {
                    if (!"updateDataSourceIdentityColor".equals(method.getName())) {
                        return null;
                    }
                    capturedId.set((Long) args[0]);
                    capturedColor.set((String) args[1]);
                    WorkspaceDataSource dataSource = new WorkspaceDataSource();
                    dataSource.setId((Long) args[0]);
                    dataSource.setIdentityColor((String) args[1]);
                    dataSource.setEnvironmentId(2L);
                    dataSource.setEnvironment(Environment.builder().id(2L).shortName("PROD").build());
                    return dataSource;
                });
        DbDataSourceController controller = new DbDataSourceController(null,
                DataSourceWebConverter.INSTANCE, null, service, null);
        DataSourceIdentityColorUpdateRequest request = new DataSourceIdentityColorUpdateRequest();
        request.setId(91L);
        request.setIdentityColor(null);

        DataResult<DataSourceIdentityColorResponse> result = controller.updateIdentityColor(request);

        PostMapping mapping = DbDataSourceController.class
                .getMethod("updateIdentityColor", DataSourceIdentityColorUpdateRequest.class)
                .getAnnotation(PostMapping.class);
        assertArrayEquals(new String[]{"/datasource/identity_color"}, mapping.value());
        assertEquals(91L, capturedId.get());
        assertNull(capturedColor.get());
        assertEquals(91L, result.getData().getId());
        assertNull(result.getData().getIdentityColor());
        assertEquals("PROD", result.getData().getEnvironment().getShortName());
    }
}
