package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.db.DbViewDeleteRequest;
import ai.chat2db.community.domain.api.service.db.IDbViewService;
import ai.chat2db.community.web.api.model.request.db.TableDeleteRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbViewControllerTest {

    @Test
    void deleteDelegatesToViewServiceWithMappedViewName() throws Exception {
        AtomicReference<DbViewDeleteRequest> droppedView = new AtomicReference<>();
        IDbViewService viewService = (IDbViewService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IDbViewService.class},
                (proxy, method, args) -> {
                    if ("drop".equals(method.getName())) {
                        droppedView.set((DbViewDeleteRequest) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbViewController controller = new DbViewController();
        Field viewServiceField = DbViewController.class.getDeclaredField("viewService");
        viewServiceField.setAccessible(true);
        viewServiceField.set(controller, viewService);

        TableDeleteRequest request = new TableDeleteRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("analytics");
        request.setSchemaName("reporting");
        request.setTableName("monthly_summary");

        controller.delete(request);

        DbViewDeleteRequest actual = droppedView.get();
        assertNotNull(actual);
        assertEquals(42L, actual.getDataSourceId());
        assertEquals("analytics", actual.getDatabaseName());
        assertEquals("reporting", actual.getSchemaName());
        assertEquals("monthly_summary", actual.getViewName());
    }
}
