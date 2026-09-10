package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.web.api.config.console.ConsoleHelper;
import ai.chat2db.community.web.api.converter.task.TaskDownloadWebConverter;
import ai.chat2db.community.web.api.model.request.task.TaskEventQueryRequest;
import ai.chat2db.community.web.api.model.request.task.TaskIdRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskControllerDesktopContractTest {

    @TempDir
    Path tempDirectory;

    @Test
    void previewRejectsRawServerPathsBeforeReadingFiles() {
        TaskController controller = new TaskController(null, null, null, null);
        var request = new ai.chat2db.community.web.api.model.request.task.TaskImportRequest();
        request.setSourceFile("C:/private/data.csv");
        org.junit.jupiter.api.Assertions.assertThrows(
                ai.chat2db.community.tools.exception.ParamBusinessException.class,
                () -> controller.previewImport(request));
    }

    @Test
    void taskEndpointsUseStaticPathsAndAtMostOneRequestObject() {
        Set<String> paths = Arrays.stream(TaskController.class.getDeclaredMethods())
                .map(this::requestMapping)
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.path()))
                .collect(Collectors.toSet());

        assertEquals(Set.of("/export", "/import", "/import/preview", "/resume", "/list", "/get", "/events", "/delete",
                "/artifact", "/artifacts", "/active-count", "/prepare-user-exit", "/abort-user-exit"), paths);

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
    void artifactEndpointForwardsThePersistedArtifactLookupKey() throws Exception {
        Path diagnostic = Files.writeString(tempDirectory.resolve("rollback-report.json"), "{}");
        AtomicReference<Object[]> invocation = new AtomicReference<>();
        TaskService service = (TaskService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {TaskService.class}, (proxy, method, args) -> {
                    if ("resolveArtifact".equals(method.getName()) && args.length == 2) {
                        invocation.set(args);
                        return TaskDownload.builder()
                                .fileName(diagnostic.getFileName().toString())
                                .fileUri(diagnostic.toUri().toString())
                                .build();
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        TaskController controller = new TaskController(service, null, new TaskDownloadWebConverter(), null);
        TaskIdRequest request = new TaskIdRequest();
        request.setTaskId(42L);
        request.setArtifactId(diagnostic.toString());

        var response = controller.artifact(request);

        assertEquals(42L, invocation.get()[0]);
        assertEquals(diagnostic.toString(), invocation.get()[1]);
        assertEquals("attachment; filename=\"rollback-report.json\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(diagnostic.toUri(), response.getBody().getURI());
    }

    private RequestMapping requestMapping(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    }
}
