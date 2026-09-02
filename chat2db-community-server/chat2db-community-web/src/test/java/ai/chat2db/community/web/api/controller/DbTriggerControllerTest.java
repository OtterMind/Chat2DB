package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbTriggerService;
import ai.chat2db.community.web.api.model.request.db.TriggerDetailRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbTriggerControllerTest {

    @Test
    void deleteAcceptsTriggerNameJsonAndDelegatesDrop() throws Exception {
        AtomicReference<String> databaseName = new AtomicReference<>();
        AtomicReference<String> schemaName = new AtomicReference<>();
        AtomicReference<String> triggerName = new AtomicReference<>();
        IDbTriggerService triggerService = (IDbTriggerService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IDbTriggerService.class},
                (proxy, method, args) -> {
                    if ("drop".equals(method.getName())) {
                        databaseName.set((String) args[0]);
                        schemaName.set((String) args[1]);
                        triggerName.set((String) args[2]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbTriggerController controller = new DbTriggerController();
        Field triggerServiceField = DbTriggerController.class.getDeclaredField("triggerService");
        triggerServiceField.setAccessible(true);
        triggerServiceField.set(controller, triggerService);

        TriggerDetailRequest request = new ObjectMapper().readValue("""
                {
                  "dataSourceId": 42,
                  "databaseName": "analytics",
                  "schemaName": "reporting",
                  "triggerName": "trg_orders_insert"
                }
                """, TriggerDetailRequest.class);

        controller.delete(request);

        PostMapping mapping = DbTriggerController.class
                .getMethod("delete", TriggerDetailRequest.class)
                .getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/delete"}, mapping.value());
        assertEquals("analytics", databaseName.get());
        assertEquals("reporting", schemaName.get());
        assertEquals("trg_orders_insert", triggerName.get());
        assertNotNull(request.getDataSourceId());
    }
}
