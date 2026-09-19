package ai.chat2db.community.web.api.config.exception;

import ai.chat2db.community.tools.exception.SystemException;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.lang.reflect.Field;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class EasyControllerExceptionHandlerTest {

    private static final String SYSTEM_ERROR_MESSAGE = "A system error occurred. Check the server logs for details.";

    private final EasyControllerExceptionHandler exceptionHandler = new EasyControllerExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Field messageSourceField;
    private MessageSource originalMessageSource;

    @BeforeEach
    void setUp() throws Exception {
        messageSourceField = I18nUtils.class.getDeclaredField("messageSourceStatic");
        messageSourceField.setAccessible(true);
        originalMessageSource = (MessageSource) messageSourceField.get(null);

        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage(I18nUtils.DEFAULT_MESSAGE_CODE, Locale.US, SYSTEM_ERROR_MESSAGE);
        messageSourceField.set(null, messageSource);
        LocaleContextHolder.setLocale(Locale.US);
    }

    @AfterEach
    void tearDown() throws Exception {
        messageSourceField.set(null, originalMessageSource);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void unexpectedExceptionResponseDoesNotExposeInternalDetails() throws Exception {
        String sensitiveMessage = "jdbc:mysql://internal.example/private?password=secret";

        ActionResult result = exceptionHandler.convert(new IllegalStateException(sensitiveMessage));
        String json = objectMapper.writeValueAsString(result);

        assertFalse(result.success());
        assertEquals(I18nUtils.DEFAULT_MESSAGE_CODE, result.errorCode());
        assertEquals(SYSTEM_ERROR_MESSAGE, result.errorMessage());
        assertNull(result.errorDetail());
        assertFalse(json.contains(sensitiveMessage));
        assertFalse(json.contains("IllegalStateException"));
        assertFalse(json.contains("java.lang"));
    }

    @Test
    void expectedParameterErrorKeepsItsClientSafeMessage() {
        ActionResult result = exceptionHandler.convert(new IllegalArgumentException("Invalid dbType"));

        assertFalse(result.success());
        assertEquals("common.paramError", result.errorCode());
        assertEquals("Invalid dbType", result.errorMessage());
        assertNull(result.errorDetail());
    }

    @Test
    void systemExceptionUsesTheSanitizedDefaultResponse() {
        ActionResult result = exceptionHandler.convert(new SystemException("internal.config.failure"));

        assertFalse(result.success());
        assertEquals(I18nUtils.DEFAULT_MESSAGE_CODE, result.errorCode());
        assertEquals(SYSTEM_ERROR_MESSAGE, result.errorMessage());
        assertNull(result.errorDetail());
    }

    @Test
    void unavailableAgentRuntimeKeepsAnActionableErrorCode() {
        ActionResult result = exceptionHandler.convert(
                new AgentRuntimeUnavailableException("PI", "environment status is BLOCKED"));

        assertFalse(result.success());
        assertEquals("agent.runtimeUnavailable", result.errorCode());
        assertEquals("Agent runtime PI is unavailable: environment status is BLOCKED", result.errorMessage());
        assertNull(result.errorDetail());
    }
}
