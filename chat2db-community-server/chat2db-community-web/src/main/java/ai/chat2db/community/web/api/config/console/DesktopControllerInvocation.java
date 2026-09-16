package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import ai.chat2db.community.web.api.util.RequestMappingUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.multipart.MultipartFile;

/** Binds the desktop JSON envelope to the same controller contract used by HTTP. */
final class DesktopControllerInvocation {
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private DesktopControllerInvocation() { }

    static Object invoke(Object controller, RequestMappingInfo mapping, ConsoleMessage message,
            ConsoleResult result) throws Exception {
        Method method = mapping.getController().getMethod(mapping.getMethod(), mapping.getParams());
        Map<String, String> path = RequestMappingUtils.pathVariables(mapping, message.getRequestUrl());
        JsonNode body = message.getMessage() == null || message.getMessage().isBlank()
                ? JSON.createObjectNode() : JSON.readTree(message.getMessage());
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            PathVariable variable = parameter.getAnnotation(PathVariable.class);
            RequestParam query = parameter.getAnnotation(RequestParam.class);
            RequestBody requestBody = parameter.getAnnotation(RequestBody.class);
            if (parameter.getType() == MultipartFile.class || parameter.getType() == MultipartFile[].class) {
                values[i] = ConsoleHelper.getValues(message.getMessage(), new Class[]{parameter.getType()}, result)[0];
            } else if (variable != null) {
                values[i] = convert(path.get(name(variable.name(), variable.value(), parameter)), parameter);
            } else if (query != null) {
                String name = name(query.name(), query.value(), parameter);
                JsonNode value = body.get(name);
                String text = value == null || value.isNull() ? null : value.asText();
                if ((text == null || text.isEmpty()) && !ValueConstants.DEFAULT_NONE.equals(query.defaultValue())) {
                    text = query.defaultValue();
                }
                if (text == null && query.required()) throw new IllegalArgumentException("Missing request parameter: " + name);
                values[i] = convert(text, parameter);
            } else if (requestBody != null) {
                if (requestBody.required() && (message.getMessage() == null || body.isNull())) {
                    throw new IllegalArgumentException("Request body is required");
                }
                values[i] = JSON.convertValue(body, JSON.constructType(parameter.getParameterizedType()));
                if (values[i] instanceof ChatRequest chat) chat.setConsoleResult(result);
            } else {
                // Keep the legacy desktop DTO, multipart and chat streaming conventions.
                values[i] = ConsoleHelper.getValues(message.getMessage(), new Class[]{parameter.getType()}, result)[0];
            }
            if (parameter.isAnnotationPresent(Valid.class) && values[i] != null) {
                Validator validator = ApplicationContextUtil.getApplicationContext().getBean(Validator.class);
                var violations = validator.validate(values[i]);
                if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
            }
        }
        Object response = method.invoke(controller, values);
        if (response instanceof CompletionStage<?> stage) {
            try {
                return stage.toCompletableFuture().get();
            } catch (ExecutionException failure) {
                throw new InvocationTargetException(failure.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        return response;
    }

    private static String name(String name, String value, Parameter parameter) {
        return !name.isEmpty() ? name : !value.isEmpty() ? value : parameter.getName();
    }

    private static Object convert(String value, Parameter parameter) {
        return DefaultConversionService.getSharedInstance().convert(value, parameter.getType());
    }
}
