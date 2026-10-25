package ai.chat2db.community.web.api.config.exception.cli;

import java.util.Map;
import java.util.UUID;

import ai.chat2db.community.tools.exception.cli.CliDomainException;
import ai.chat2db.community.tools.exception.cli.CliErrorMessages;
import ai.chat2db.community.web.api.config.cli.security.CliRuntimeOnly;
import ai.chat2db.community.web.api.controller.CliDatasourceController;
import ai.chat2db.community.web.api.controller.CliMetadataController;
import ai.chat2db.community.web.api.controller.CliRuntimeController;
import ai.chat2db.community.web.api.controller.CliSqlController;
import ai.chat2db.community.web.api.model.response.cli.CliResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        CliRuntimeController.class,
        CliDatasourceController.class,
        CliMetadataController.class,
        CliSqlController.class
})
@CliRuntimeOnly
@Slf4j
public class CliExceptionHandler {

    @ExceptionHandler(CliDomainException.class)
    public ResponseEntity<CliResult<Void>> handleCliResult(CliDomainException exception, HttpServletRequest request) {
        String requestId = requestId(request);
        CliErrorMessages.PublicError publicError = CliErrorMessages.publicError(exception.getCode());
        log.error("CLI domain error, code={}, requestId={}, exceptionType={}", exception.getCode(), requestId,
                exception.getClass().getName());
        return ResponseEntity.badRequest()
                .body(CliResult.error(publicError.code(), publicError.message(), Map.of(), requestId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CliResult<Void>> handleValidation(MethodArgumentNotValidException exception,
                                                            HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("CLI validation error, requestId={}, exceptionType={}", requestId,
                exception.getClass().getName());
        return ResponseEntity.badRequest()
                .body(CliResult.error(CliErrorMessages.CLI_REQUEST_INVALID,
                        CliErrorMessages.CLI_REQUEST_INVALID_MESSAGE, Map.of(), requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CliResult<Void>> handleException(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("CLI runtime error, requestId={}, exceptionType={}", requestId,
                exception.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CliResult.error(CliErrorMessages.CLI_RUNTIME_ERROR,
                        CliErrorMessages.CLI_RUNTIME_ERROR_MESSAGE, Map.of(), requestId));
    }

    private String requestId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (StringUtils.isBlank(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        return requestId;
    }

}
