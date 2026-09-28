package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbDataSourceImportService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.converter.data.source.DataSourceWebConverter;
import ai.chat2db.community.web.api.model.response.data.source.ProgressResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbDataSourceControllerImportCommunityTest {

    @Test
    void importCommunityEndpointAcceptsPostOnly() throws Exception {
        Method method = DbDataSourceController.class.getMethod("importChat2db");

        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);

        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/datasource/import_community"}, mapping.value());
        assertArrayEquals(new RequestMethod[] {RequestMethod.POST}, mapping.method());
    }

    @Test
    void importCommunityInvokesDatasourceImportService() {
        AtomicInteger imports = new AtomicInteger();
        IDbDataSourceImportService importService = (IDbDataSourceImportService) Proxy.newProxyInstance(
                IDbDataSourceImportService.class.getClassLoader(),
                new Class<?>[] {IDbDataSourceImportService.class},
                (proxy, method, args) -> {
                    if ("importCommunityDataSources".equals(method.getName())) {
                        imports.incrementAndGet();
                    }
                    return null;
                });
        DbDataSourceController controller = new DbDataSourceController(null,
                DataSourceWebConverter.INSTANCE, null, null, importService);

        DataResult<ProgressResponse> result = controller.importChat2db();

        assertEquals(1, imports.get());
        assertNotNull(result.getData());
    }
}
