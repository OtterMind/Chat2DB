package ai.chat2db.community.web.api.config.exception.cli;

import ai.chat2db.community.web.api.controller.CliSqlController;

import ai.chat2db.community.web.api.controller.CliRuntimeController;

import ai.chat2db.community.web.api.controller.CliMetadataController;

import ai.chat2db.community.web.api.controller.CliDatasourceController;

import java.util.UUID;
import java.util.Map;

import ai.chat2db.community.web.api.config.cli.security.CliRuntimeOnly;
import ai.chat2db.community.web.api.model.response.cli.CliResult;
import ai.chat2db.community.tools.exception.cli.CliDomainException;
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
        return ResponseEntity.badRequest()
                .body(CliResult.error(defaultCode(exception.getCode()), exception.getMessage(),
                        exception.getDetails(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CliResult<Void>> handleValidation(MethodArgumentNotValidException exception,
                                                            HttpServletRequest request) {
        log.error("CLI validation error", exception);
        return ResponseEntity.badRequest()
                .body(CliResult.error("cli_request_invalid", "Request validation failed",
                        Map.of(), requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CliResult<Void>> handleException(Exception exception, HttpServletRequest request) {
        log.error("CLI runtime error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CliResult.error("cli_runtime_error", "An internal error occurred",
                        Map.of(), requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (StringUtils.isBlank(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        return requestId;
    }

    private String defaultCode(String code) {
        return StringUtils.defaultIfBlank(code, "cli_runtime_error");
    }
}
