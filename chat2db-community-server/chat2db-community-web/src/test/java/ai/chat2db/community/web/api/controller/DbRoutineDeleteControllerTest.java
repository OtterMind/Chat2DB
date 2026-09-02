package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbFunctionService;
import ai.chat2db.community.domain.api.service.db.IDbProcedureService;
import ai.chat2db.community.web.api.model.request.db.FunctionDetailRequest;
import ai.chat2db.community.web.api.model.request.db.ProcedureDetailRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbRoutineDeleteControllerTest {

    @Test
    void functionDeleteAcceptsFunctionNameJsonAndDelegatesDrop() throws Exception {
        AtomicReference<String> databaseName = new AtomicReference<>();
        AtomicReference<String> schemaName = new AtomicReference<>();
        AtomicReference<String> functionName = new AtomicReference<>();
        IDbFunctionService functionService = (IDbFunctionService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IDbFunctionService.class},
                (proxy, method, args) -> {
                    if ("drop".equals(method.getName())) {
                        databaseName.set((String) args[0]);
                        schemaName.set((String) args[1]);
                        functionName.set((String) args[2]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbFunctionController controller = new DbFunctionController();
        Field functionServiceField = DbFunctionController.class.getDeclaredField("functionService");
        functionServiceField.setAccessible(true);
        functionServiceField.set(controller, functionService);

        FunctionDetailRequest request = new ObjectMapper().readValue("""
                {
                  "dataSourceId": 42,
                  "databaseName": "analytics",
                  "schemaName": "reporting",
                  "functionName": "calc_tax"
                }
                """, FunctionDetailRequest.class);

        controller.delete(request);

        PostMapping mapping = DbFunctionController.class
                .getMethod("delete", FunctionDetailRequest.class)
                .getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/delete"}, mapping.value());
        assertEquals("analytics", databaseName.get());
        assertEquals("reporting", schemaName.get());
        assertEquals("calc_tax", functionName.get());
        assertNotNull(request.getDataSourceId());
    }

    @Test
    void procedureDeleteAcceptsProcedureNameJsonAndDelegatesDrop() throws Exception {
        AtomicReference<String> databaseName = new AtomicReference<>();
        AtomicReference<String> schemaName = new AtomicReference<>();
        AtomicReference<String> procedureName = new AtomicReference<>();
        IDbProcedureService procedureService = (IDbProcedureService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IDbProcedureService.class},
                (proxy, method, args) -> {
                    if ("drop".equals(method.getName())) {
                        databaseName.set((String) args[0]);
                        schemaName.set((String) args[1]);
                        procedureName.set((String) args[2]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbProcedureController controller = new DbProcedureController();
        Field procedureServiceField = DbProcedureController.class.getDeclaredField("procedureService");
        procedureServiceField.setAccessible(true);
        procedureServiceField.set(controller, procedureService);

        ProcedureDetailRequest request = new ObjectMapper().readValue("""
                {
                  "dataSourceId": 42,
                  "databaseName": "analytics",
                  "schemaName": "reporting",
                  "procedureName": "rebuild_rollups"
                }
                """, ProcedureDetailRequest.class);

        controller.delete(request);

        PostMapping mapping = DbProcedureController.class
                .getMethod("delete", ProcedureDetailRequest.class)
                .getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/delete"}, mapping.value());
        assertEquals("analytics", databaseName.get());
        assertEquals("reporting", schemaName.get());
        assertEquals("rebuild_rollups", procedureName.get());
        assertNotNull(request.getDataSourceId());
    }
}
