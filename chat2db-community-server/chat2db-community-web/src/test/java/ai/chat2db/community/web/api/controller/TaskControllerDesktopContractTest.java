package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.web.api.config.console.ConsoleHelper;
import ai.chat2db.community.web.api.converter.task.TaskWebConverter;
import ai.chat2db.community.web.api.model.request.task.TaskEventQueryRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskControllerDesktopContractTest {

    @Test
    void taskEndpointsUseStaticPathsAndAtMostOneRequestObject() {
        Set<String> paths = Arrays.stream(TaskController.class.getDeclaredMethods())
                .map(this::requestMapping)
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.path()))
                .collect(Collectors.toSet());

        assertEquals(Set.of("/export", "/import", "/list", "/get", "/events", "/delete", "/cancel",
                "/artifact", "/active-count", "/prepare-user-exit", "/abort-user-exit"), paths);

        Arrays.stream(TaskController.class.getDeclaredMethods())
                .filter(method -> requestMapping(method) != null)
                .forEach(method -> {
                    RequestMapping mapping = requestMapping(method);
                    assertTrue(method.getParameterCount() <= 1,
                            () -> method.getName() + " must accept at most one request object");
                    Arrays.stream(mapping.path()).forEach(path -> assertFalse(path.contains("{"),
                            () -> method.getName() + " must not use path variables"));
                });
    }

    @Test
    void eventQueryUsesTheDomainDefaultWhenLimitIsMissing() {
        TaskEventQueryRequest request = new TaskEventQueryRequest();
        assertEquals(TaskConstants.DEFAULT_EVENT_LIMIT, request.effectiveLimit());

        request.setLimit(null);
        assertEquals(TaskConstants.DEFAULT_EVENT_LIMIT, request.effectiveLimit());
    }

    @Test
    void desktopBridgeDeserializesEventQueryAsOneRequestObject() {
        Object[] values = ConsoleHelper.getValues(
                "{\"taskId\":42,\"afterSequence\":10,\"limit\":20}",
                new Class<?>[] {TaskEventQueryRequest.class},
                new ConsoleResult());

        TaskEventQueryRequest request = assertInstanceOf(TaskEventQueryRequest.class, values[0]);
        assertEquals(42L, request.getTaskId());
        assertEquals(10L, request.getAfterSequence());
        assertEquals(20, request.effectiveLimit());
    }

    @Test
    void legacyImportEndpointKeepsSupportedRawPathDataFileImports() {
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<String> lastFormat = new AtomicReference<>();
        TaskService taskService = (TaskService) Proxy.newProxyInstance(
                TaskControllerDesktopContractTest.class.getClassLoader(),
                new Class<?>[] {TaskService.class},
                (proxy, method, args) -> {
                    if ("submitImport".equals(method.getName())) {
                        submissions.incrementAndGet();
                        lastFormat.set(((ai.chat2db.community.domain.api.model.task.ImportTaskSpec) args[0])
                                .getFormat());
                        return 42L;
                    }
                    return null;
                });
        TaskController controller = new TaskController(taskService, new TaskWebConverter(), null);

        for (TaskFileFormat format : List.of(TaskFileFormat.CSV, TaskFileFormat.JSON,
                TaskFileFormat.XLS, TaskFileFormat.XLSX)) {
            TaskImportRequest request = dataFileImportRequest(format);

            assertEquals(42L, controller.submitImport(request).getData().getTaskId());
            assertEquals(format.name(), lastFormat.get());
        }
        assertEquals(4, submissions.get());
    }

    @Test
    void legacyImportEndpointPreservesParserOptionsForLocalExcelImport() {
        AtomicReference<ai.chat2db.community.domain.api.model.task.ImportTaskSpec> submitted =
                new AtomicReference<>();
        TaskService taskService = (TaskService) Proxy.newProxyInstance(
                TaskControllerDesktopContractTest.class.getClassLoader(),
                new Class<?>[] {TaskService.class},
                (proxy, method, args) -> {
                    if ("submitImport".equals(method.getName())) {
                        submitted.set((ai.chat2db.community.domain.api.model.task.ImportTaskSpec) args[0]);
                        return 42L;
                    }
                    return null;
                });
        TaskController controller = new TaskController(taskService, new TaskWebConverter(), null);
        TaskImportRequest request = dataFileImportRequest(TaskFileFormat.XLSX);
        request.setImportOptions(Map.of("sheetName", "Orders", "headerRow", 2, "startRow", 3));

        assertEquals(42L, controller.submitImport(request).getData().getTaskId());

        assertEquals(Map.of("sheetName", "Orders", "headerRow", 2, "startRow", 3),
                submitted.get().getImportOptions());
    }

    private TaskImportRequest dataFileImportRequest(TaskFileFormat format) {
        TaskImportRequest request = new TaskImportRequest();
        request.setDatabaseName("app");
        request.setTableName("orders");
        request.setTaskType(TaskType.DATA_FILE_IMPORT.name());
        request.setFormat(format.name());
        request.setSourceFile("/tmp/orders." + format.name().toLowerCase());
        return request;
    }

    private RequestMapping requestMapping(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    }
}
