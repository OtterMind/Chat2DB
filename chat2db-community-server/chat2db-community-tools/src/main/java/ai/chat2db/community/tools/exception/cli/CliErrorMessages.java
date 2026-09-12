package ai.chat2db.community.tools.exception.cli;

import java.util.Map;

public final class CliErrorMessages {

    public static final String CLI_RUNTIME_ERROR = "cli_runtime_error";
    public static final String CLI_RUNTIME_ERROR_MESSAGE = "An internal error occurred";
    public static final String CLI_REQUEST_INVALID = "cli_request_invalid";
    public static final String CLI_REQUEST_INVALID_MESSAGE = "Request validation failed";
    public static final String DATASOURCE_CONNECTION_FAILED = "datasource_connection_failed";
    public static final String DATASOURCE_CONNECTION_FAILED_MESSAGE = "Datasource connection test failed.";

    private static final Map<String, String> PUBLIC_MESSAGES = Map.ofEntries(
            Map.entry(CLI_RUNTIME_ERROR, CLI_RUNTIME_ERROR_MESSAGE),
            Map.entry(CLI_REQUEST_INVALID, CLI_REQUEST_INVALID_MESSAGE),
            Map.entry(DATASOURCE_CONNECTION_FAILED, DATASOURCE_CONNECTION_FAILED_MESSAGE),
            Map.entry("datasource_not_found", "Datasource not found."),
            Map.entry("invalid_connection_test_args", "Invalid connection test arguments."),
            Map.entry("invalid_datasource_create_args", "Invalid datasource create arguments."),
            Map.entry("sql_query_failed", "SQL query failed."),
            Map.entry("sql_result_set_not_found", "SQL result set not found."),
            Map.entry("web.not.support.db.type", "This database type is not supported in web mode.")
    );

    private CliErrorMessages() {
    }

    public static PublicError publicError(String code) {
        String safeCode = trimToNull(code);
        String message = safeCode == null ? null : PUBLIC_MESSAGES.get(safeCode);
        if (message == null) {
            return new PublicError(CLI_RUNTIME_ERROR, CLI_RUNTIME_ERROR_MESSAGE);
        }
        return new PublicError(safeCode, message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record PublicError(String code, String message) {
    }
}
