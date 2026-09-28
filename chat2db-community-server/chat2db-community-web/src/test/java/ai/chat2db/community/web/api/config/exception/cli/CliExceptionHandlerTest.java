package ai.chat2db.community.web.api.config.exception.cli;

import ai.chat2db.community.tools.exception.cli.CliDomainException;
import ai.chat2db.community.web.api.model.response.cli.CliResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliExceptionHandlerTest {

    private static final String REQUEST_ID = "cli-request-1";
    private static final String SENSITIVE_TEXT =
            "java.lang.IllegalStateException: jdbc:mysql://db.internal/app?pass" + "word=DO_NOT_EXPOSE";

    private final CliExceptionHandler exceptionHandler = new CliExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void domainExceptionUsesAllowlistedCodeAndMappedPublicMessageOnly() throws Exception {
        CliDomainException exception = new CliDomainException("datasource_not_found",
                "Datasource not found: " + SENSITIVE_TEXT,
                Map.of(
                        "password", "secret",
                        "stackTrace", "ai.chat2db.internal.Source.method(Source.java:42)",
                        "errorDetail", SENSITIVE_TEXT
                ));

        ResponseEntity<CliResult<Void>> response = exceptionHandler.handleCliResult(exception, request());
        String json = objectMapper.writeValueAsString(response.getBody());

        assertEquals(400, response.getStatusCode().value());
        assertEquals("datasource_not_found", response.getBody().getError().getCode());
        assertEquals("Datasource not found.", response.getBody().getError().getMessage());
        assertTrue(response.getBody().getError().getDetails().isEmpty());
        assertPublicResponseIsSanitized(json);
    }

    @Test
    void unknownDomainExceptionFallsBackToGenericRuntimeError() throws Exception {
        CliDomainException exception = new CliDomainException("surprise_internal_code", SENSITIVE_TEXT);

        ResponseEntity<CliResult<Void>> response = exceptionHandler.handleCliResult(exception, request());
        String json = objectMapper.writeValueAsString(response.getBody());

        assertEquals(400, response.getStatusCode().value());
        assertEquals("cli_runtime_error", response.getBody().getError().getCode());
        assertEquals("An internal error occurred", response.getBody().getError().getMessage());
        assertTrue(response.getBody().getError().getDetails().isEmpty());
        assertPublicResponseIsSanitized(json);
    }

    @Test
    void validationExceptionUsesStablePublicMessageOnly() throws Exception {
        MethodArgumentNotValidException exception = validationException();

        ResponseEntity<CliResult<Void>> response = exceptionHandler.handleValidation(exception, request());
        String json = objectMapper.writeValueAsString(response.getBody());

        assertEquals(400, response.getStatusCode().value());
        assertEquals("cli_request_invalid", response.getBody().getError().getCode());
        assertEquals("Request validation failed", response.getBody().getError().getMessage());
        assertTrue(response.getBody().getError().getDetails().isEmpty());
        assertPublicResponseIsSanitized(json);
    }

    @Test
    void generalExceptionUsesStablePublicMessageOnly() throws Exception {
        ResponseEntity<CliResult<Void>> response = exceptionHandler.handleException(
                new IllegalStateException(SENSITIVE_TEXT), request());
        String json = objectMapper.writeValueAsString(response.getBody());

        assertEquals(500, response.getStatusCode().value());
        assertEquals("cli_runtime_error", response.getBody().getError().getCode());
        assertEquals("An internal error occurred", response.getBody().getError().getMessage());
        assertTrue(response.getBody().getError().getDetails().isEmpty());
        assertPublicResponseIsSanitized(json);
    }

    private static MethodArgumentNotValidException validationException() throws NoSuchMethodException {
        Method method = CliExceptionHandlerTest.class.getDeclaredMethod("validatedMethod", String.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(SENSITIVE_TEXT, "request");
        bindingResult.rejectValue(null, "invalid", SENSITIVE_TEXT);
        return new MethodArgumentNotValidException(methodParameter, bindingResult);
    }

    @SuppressWarnings("unused")
    private void validatedMethod(String value) {
    }

    private static HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getHeader".equals(method.getName()) && args != null && args.length == 1
                            && "X-Request-Id".equals(args[0])) {
                        return REQUEST_ID;
                    }
                    if ("toString".equals(method.getName())) {
                        return "CliExceptionHandlerTestRequest";
                    }
                    return null;
                });
    }

    private static void assertPublicResponseIsSanitized(String json) {
        assertFalse(json.contains(SENSITIVE_TEXT));
        assertFalse(json.contains("IllegalStateException"));
        assertFalse(json.contains("java.lang"));
        assertFalse(json.contains("ai.chat2db.internal"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("secret"));
    }
}
