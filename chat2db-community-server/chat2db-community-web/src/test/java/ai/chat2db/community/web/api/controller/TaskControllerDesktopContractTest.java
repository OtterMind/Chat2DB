package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.web.api.config.console.ConsoleHelper;
import ai.chat2db.community.web.api.model.request.task.TaskEventQueryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskControllerDesktopContractTest {

    @Test
    void taskEndpointsUseStaticPathsAndAtMostOneRequestObject() {
        Set<String> paths = Arrays.stream(TaskController.class.getDeclaredMethods())
                .map(this::requestMapping)
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.path()))
                .collect(Collectors.toSet());

        assertEquals(Set.of("/export", "/import", "/list", "/get", "/events", "/delete",
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

    private RequestMapping requestMapping(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    }
}
