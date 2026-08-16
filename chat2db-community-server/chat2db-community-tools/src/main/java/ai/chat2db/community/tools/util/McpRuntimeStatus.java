package ai.chat2db.community.tools.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class McpRuntimeStatus {

    public enum RuntimeState {
        UNKNOWN,
        STARTING,
        RUNNING,
        STOPPED,
        FAILED
    }

    private static volatile boolean appliedEnabled;
    private static volatile RuntimeState runtimeState = RuntimeState.UNKNOWN;
    private static volatile String failureMessage;

    private McpRuntimeStatus() {
    }

    public static synchronized void initialize(boolean enabled) {
        appliedEnabled = enabled;
        runtimeState = enabled ? RuntimeState.STARTING : RuntimeState.STOPPED;
        failureMessage = null;
    }

    public static synchronized void markReady() {
        runtimeState = appliedEnabled ? RuntimeState.RUNNING : RuntimeState.STOPPED;
        failureMessage = null;
    }

    public static synchronized void markFailed(Throwable throwable) {
        runtimeState = RuntimeState.FAILED;
        failureMessage = throwable == null ? null : throwable.getMessage();
    }

    public static synchronized Map<String, Object> snapshot(String operationId) {
        return snapshot(operationId, SystemSettingsUtil.isMcpEnabled());
    }

    static synchronized Map<String, Object> snapshot(String operationId, boolean configuredEnabled) {
        String resolvedOperationId = operationId == null || operationId.isBlank()
                ? UUID.randomUUID().toString()
                : operationId;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("operationId", resolvedOperationId);
        status.put("configuredEnabled", configuredEnabled);
        status.put("appliedEnabled", appliedEnabled);
        status.put("runtimeState", runtimeState.name());
        status.put("restartRequired", configuredEnabled != appliedEnabled);
        if (failureMessage != null && !failureMessage.isBlank()) {
            status.put("failureMessage", failureMessage);
        }
        return status;
    }
}
