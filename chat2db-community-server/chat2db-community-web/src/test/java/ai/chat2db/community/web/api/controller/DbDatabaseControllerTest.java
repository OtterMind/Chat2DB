package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseCreateRequest;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseService;
import ai.chat2db.community.web.api.model.request.db.UpdateDatabaseRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbDatabaseControllerTest {

    @Test
    void modifyDatabaseDelegatesWithOriginalAndNewDatabaseNames() throws Exception {
        AtomicReference<DbDatabaseCreateRequest> captured = new AtomicReference<>();
        IDbDatabaseService databaseService = (IDbDatabaseService) Proxy.newProxyInstance(
                IDbDatabaseService.class.getClassLoader(),
                new Class<?>[] {IDbDatabaseService.class},
                (proxy, method, args) -> {
                    if ("modifyDatabase".equals(method.getName())) {
                        captured.set((DbDatabaseCreateRequest) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbDatabaseController controller = new DbDatabaseController();
        Field databaseServiceField = DbDatabaseController.class.getDeclaredField("databaseService");
        databaseServiceField.setAccessible(true);
        databaseServiceField.set(controller, databaseService);

        UpdateDatabaseRequest request = new UpdateDatabaseRequest();
        request.setDatabaseName("legacy_db");
        request.setNewDatabaseName("renamed_db");

        controller.modifyDatabase(request);

        DbDatabaseCreateRequest actual = captured.get();
        assertNotNull(actual);
        assertEquals("legacy_db", actual.getName());
        assertEquals("renamed_db", actual.getNewName());
    }
}
