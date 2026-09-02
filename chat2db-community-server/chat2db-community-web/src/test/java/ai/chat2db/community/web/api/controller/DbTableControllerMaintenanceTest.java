package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.task.TableMaintenanceTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.TableDetailQueryRequest;
import ai.chat2db.community.web.api.model.response.task.TaskSubmitResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbTableControllerMaintenanceTest {

    @Test
    void maintenanceSqlReadsOperationTypeFromPostBodyUsedByFrontendService() throws Exception {
        AtomicReference<DbTableQueryRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<String> capturedOperation = new AtomicReference<>();
        IDbTableService tableService = (IDbTableService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IDbTableService.class},
                (proxy, method, args) -> {
                    if ("maintenanceSql".equals(method.getName())) {
                        capturedRequest.set((DbTableQueryRequest) args[0]);
                        capturedOperation.set((String) args[1]);
                        return "REPAIR TABLE `shop`.`orders`";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbTableController controller = new DbTableController(null);
        setField(controller, "tableService", tableService);
        setField(controller, "dbWebConverter", Mappers.getMapper(DbWebConverter.class));

        TableDetailQueryRequest request = new TableDetailQueryRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");
        request.setTableName("orders");
        request.setOperationType("REPAIR");

        DataResult<String> result = controller.maintenanceSql(request);

        assertEquals("REPAIR TABLE `shop`.`orders`", result.getData());
        assertEquals("REPAIR", capturedOperation.get());
        assertEquals("orders", capturedRequest.get().getTableName());
    }

    @Test
    void executeMaintenanceSubmitsTaskWithOperationAndTableTarget() throws Exception {
        AtomicReference<TableMaintenanceTaskSpec> capturedSpec = new AtomicReference<>();
        TaskService taskService = (TaskService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TaskService.class},
                (proxy, method, args) -> {
                    if ("submitTableMaintenance".equals(method.getName())) {
                        capturedSpec.set((TableMaintenanceTaskSpec) args[0]);
                        return 91L;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbTableController controller = new DbTableController(null);
        setField(controller, "taskService", taskService);

        TableDetailQueryRequest request = new TableDetailQueryRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");
        request.setSchemaName("public");
        request.setTableName("orders");
        request.setOperationType("ANALYZE");

        DataResult<TaskSubmitResponse> result = controller.executeMaintenance(request);

        assertEquals(91L, result.getData().getTaskId());
        assertEquals(TaskType.TABLE_MAINTENANCE.name(), capturedSpec.get().getTaskType());
        assertEquals("ANALYZE", capturedSpec.get().getOperationType());
        assertEquals("ANALYZE TABLE - shop.orders", capturedSpec.get().getTaskName());
        assertEquals(7L, capturedSpec.get().getTarget().getDataSourceId());
        assertEquals("shop", capturedSpec.get().getTarget().getDatabaseName());
        assertEquals("public", capturedSpec.get().getTarget().getSchemaName());
        assertEquals("orders", capturedSpec.get().getTarget().getTableName());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = DbTableController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
