package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceCloseRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDataSourceControllerTest {

    @Test
    void closeConnectionUsesPostBodyAndDelegatesConnectionId() throws Exception {
        AtomicReference<Long> closedConnectionId = new AtomicReference<>();
        IDbDataSourceService dataSourceService = (IDbDataSourceService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IDbDataSourceService.class},
                (proxy, method, args) -> {
                    if ("removeConnection".equals(method.getName())) {
                        closedConnectionId.set((Long) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DbDataSourceController controller = new DbDataSourceController(dataSourceService, null, null, null, null);
        DataSourceCloseRequest request = new DataSourceCloseRequest();
        request.setId(42L);

        controller.closeConnection(request);

        assertEquals(42L, closedConnectionId.get());

        Method closeConnection = DbDataSourceController.class.getMethod(
                "closeConnection", DataSourceCloseRequest.class);
        PostMapping postMapping = closeConnection.getAnnotation(PostMapping.class);
        assertNotNull(postMapping);
        assertArrayEquals(new String[] {"/close"}, postMapping.value());
        assertNull(closeConnection.getAnnotation(GetMapping.class));
        assertTrue(closeConnection.getParameters()[0].isAnnotationPresent(RequestBody.class));
        assertTrue(closeConnection.getParameters()[0].isAnnotationPresent(Valid.class));
    }
}
