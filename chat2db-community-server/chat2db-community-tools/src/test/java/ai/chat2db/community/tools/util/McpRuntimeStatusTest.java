package ai.chat2db.community.tools.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpRuntimeStatusTest {

    @Test
    void reportsStartingRunningAndRestartRequiredFromAppliedState() {
        McpRuntimeStatus.initialize(true);

        Map<String, Object> starting = McpRuntimeStatus.snapshot("load-1", true);
        assertEquals("load-1", starting.get("operationId"));
        assertEquals("STARTING", starting.get("runtimeState"));
        assertEquals(false, starting.get("restartRequired"));

        McpRuntimeStatus.markReady();
        Map<String, Object> running = McpRuntimeStatus.snapshot("save-1", false);
        assertEquals("RUNNING", running.get("runtimeState"));
        assertEquals(true, running.get("appliedEnabled"));
        assertEquals(false, running.get("configuredEnabled"));
        assertEquals(true, running.get("restartRequired"));
    }

    @Test
    void reportsStoppedAndFailureStates() {
        McpRuntimeStatus.initialize(false);
        McpRuntimeStatus.markReady();
        assertEquals("STOPPED", McpRuntimeStatus.snapshot("load-2", false).get("runtimeState"));

        McpRuntimeStatus.markFailed(new IllegalStateException("bind failed"));
        Map<String, Object> failed = McpRuntimeStatus.snapshot("load-3", false);
        assertEquals("FAILED", failed.get("runtimeState"));
        assertEquals("bind failed", failed.get("failureMessage"));
    }
}
