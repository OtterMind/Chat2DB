package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.db.DbImportTemplateDeleteRequest;
import ai.chat2db.community.web.api.model.request.db.DbImportTemplateSaveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbImportTemplateControllerContractTest {

    @Test
    void templateEndpointsStayOnTheImportPath() {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(DbImportTemplateController.class,
                RequestMapping.class);

        assertEquals(Set.of("/api/rdb/import/templates"), Set.of(mapping.path()));
    }

    @Test
    void templatesAreGlobalSoTheEndpointsTakeNoTableScope() {
        Method list = method("list");
        Method save = method("save");
        Method delete = method("delete");

        assertTrue(AnnotatedElementUtils.hasAnnotation(list, GetMapping.class));
        assertTrue(AnnotatedElementUtils.hasAnnotation(save, PostMapping.class));
        assertTrue(AnnotatedElementUtils.hasAnnotation(delete, DeleteMapping.class));
        // The only scope a template endpoint takes is the optional file type.
        assertEquals(Set.of(String.class), parameterTypes(list));
        assertEquals(Set.of(DbImportTemplateSaveRequest.class), parameterTypes(save));
        assertEquals(Set.of(DbImportTemplateDeleteRequest.class), parameterTypes(delete));
    }

    @Test
    void theTemplateListTakesAnOptionalFileTypeQueryParameter() {
        RequestParam fileType = AnnotatedElementUtils.findMergedAnnotation(
                method("list").getParameters()[0], RequestParam.class);

        assertAll(
                () -> assertNotNull(fileType),
                () -> assertEquals("fileType", fileType.value()),
                () -> assertFalse(fileType.required(), "listing every file type stays the default")
        );
    }

    @Test
    void endpointMethodsAcceptAtMostOneRequestObjectAndNoPathVariables() {
        Map<String, String> paths = new HashMap<>();
        Arrays.stream(DbImportTemplateController.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("list") || method.getName().startsWith("save")
                        || method.getName().startsWith("delete"))
                .forEach(method -> {
                    assertTrue(method.getParameterCount() <= 1,
                            () -> method.getName() + " must accept at most one request object");
                    Arrays.stream(requestPaths(method)).forEach(path -> paths.put(method.getName(), path));
                });

        assertFalse(paths.values().stream().anyMatch(path -> path.contains("{")),
                () -> "template endpoints must not use path variables: " + paths);
    }

    private Method method(String name) {
        return Arrays.stream(DbImportTemplateController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing endpoint method " + name));
    }

    private Set<Class<?>> parameterTypes(Method method) {
        return Set.of(method.getParameterTypes());
    }

    private String[] requestPaths(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        return mapping == null ? new String[0] : mapping.path();
    }
}
