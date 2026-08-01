package ai.chat2db.community.start.ai.subscription.routing.mcp;

/**
 * Invokes an allowlisted Chat2DB tool for the unique active attempt.
 * Implementations must not log arguments or result bodies.
 */
@FunctionalInterface
public interface McpToolCallHandler {

    McpToolCallResult call(String attemptId, String toolName, String argumentsJson);

    record McpToolCallResult(boolean success, String responseText, String errorCode) {

        public static McpToolCallResult ok(String responseText) {
            return new McpToolCallResult(true, responseText == null ? "" : responseText, null);
        }

        public static McpToolCallResult error(String errorCode) {
            return new McpToolCallResult(false, "", errorCode);
        }
    }
}
