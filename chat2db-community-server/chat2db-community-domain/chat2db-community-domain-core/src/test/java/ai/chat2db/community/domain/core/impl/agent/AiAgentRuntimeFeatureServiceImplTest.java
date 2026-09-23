package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.agent.PiRuntimeInstallTaskSpec;
import ai.chat2db.community.domain.api.service.agent.IAgentFeatureFlagStorage;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEnvironmentChecker;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentRuntimeFeatureServiceImplTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void enablesOnlyAfterInstallationAndVerification() {
        MemoryFlags flags = new MemoryFlags();
        AtomicInteger installs = new AtomicInteger();
        AiAgentRuntimeFeatureServiceImpl service = new AiAgentRuntimeFeatureServiceImpl(
                flags, checker(AgentRuntimeEnvironmentStatus.READY), environment -> {
                    installs.incrementAndGet();
                    return temporaryDirectory;
                });

        assertFalse(service.check(environment()).enabled());
        assertTrue(service.enable(environment()).enabled());
        assertEquals(1, installs.get());
        assertTrue(flags.isEnabled(AgentRuntimeType.PI));
        assertFalse(service.disable(environment()).enabled());
    }

    @Test
    void keepsFeatureDisabledWhenInstallationFails() {
        MemoryFlags flags = new MemoryFlags();
        AiAgentRuntimeFeatureServiceImpl service = new AiAgentRuntimeFeatureServiceImpl(
                flags, checker(AgentRuntimeEnvironmentStatus.BLOCKED), environment -> {
                    throw new IOException("download failed");
                });

        var state = service.enable(environment());

        assertFalse(state.enabled());
        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED, state.environment().status());
        assertEquals(List.of("INSTALL_FAILED"), state.environment().checks());
        assertFalse(flags.isEnabled(AgentRuntimeType.PI));
    }

    @Test
    void submitsOneInstallationAndEnablesAfterTaskSuccess() {
        MemoryFlags flags = new MemoryFlags();
        TaskRecord tasks = new TaskRecord();
        AiAgentRuntimeFeatureServiceImpl service = new AiAgentRuntimeFeatureServiceImpl(
                flags, checker(AgentRuntimeEnvironmentStatus.READY), environment -> temporaryDirectory, tasks.service());

        assertEquals(1L, service.enableAsync(environment()).taskId());
        assertFalse(flags.isEnabled(AgentRuntimeType.PI));
        assertEquals(1L, service.enableAsync(environment()).taskId());
        assertEquals(1, tasks.submissions);
        assertEquals(environment(), tasks.spec.getEnvironment());
        tasks.task.setStatus("SUCCESS");

        var completed = service.enableAsync(environment());
        assertTrue(completed.enabled());
        assertNull(completed.taskId());
        assertEquals(1, tasks.submissions);
    }

    @Test
    void findsExistingInstallTaskAndKeepsFeatureDisabledAfterFailure() {
        MemoryFlags flags = new MemoryFlags();
        TaskRecord tasks = new TaskRecord();
        tasks.task = new Task();
        tasks.task.setId(12L);
        tasks.task.setType("PI_RUNTIME_INSTALL");
        tasks.task.setStatus("RUNNING");
        AiAgentRuntimeFeatureServiceImpl service = new AiAgentRuntimeFeatureServiceImpl(
                flags, checker(AgentRuntimeEnvironmentStatus.BLOCKED), environment -> temporaryDirectory, tasks.service());

        assertEquals(12L, service.enableAsync(environment()).taskId());
        assertEquals(0, tasks.submissions);
        tasks.task.setStatus("FAILED");
        var failed = service.enableAsync(environment());
        assertFalse(failed.enabled());
        assertNull(failed.taskId());
        assertFalse(flags.isEnabled(AgentRuntimeType.PI));
    }

    private IAgentRuntimeEnvironmentChecker checker(AgentRuntimeEnvironmentStatus status) {
        return request -> new AgentRuntimeEnvironmentReport(
                AgentRuntimeType.PI, status, "0.85.1", "macos", "arm64",
                List.of(), Map.of(), LocalDateTime.of(2026, 9, 9, 0, 0));
    }

    private AgentRuntimeEnvironmentRequest environment() {
        return new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64");
    }

    private static final class TaskRecord {
        private Task task;
        private PiRuntimeInstallTaskSpec spec;
        private int submissions;

        TaskService service() {
            return (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(),
                    new Class<?>[] {TaskService.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "submitImport" -> {
                            spec = (PiRuntimeInstallTaskSpec) args[0];
                            submissions++;
                            task = new Task();
                            task.setId(1L);
                            task.setType(spec.getTaskType());
                            task.setStatus("PENDING");
                            yield task.getId();
                        }
                        case "get" -> task;
                        case "list" -> PageResponse.of(task == null ? List.of() : List.of(task),
                                task == null ? 0L : 1L, 1, 50);
                        default -> throw new AssertionError("Unexpected task call: " + method.getName());
                    });
        }
    }

    private static final class MemoryFlags implements IAgentFeatureFlagStorage {
        private final Map<AgentRuntimeType, Boolean> values = new HashMap<>();
        @Override public boolean isEnabled(AgentRuntimeType runtimeType) {
            return values.getOrDefault(runtimeType, false);
        }
        @Override public void setEnabled(AgentRuntimeType runtimeType, boolean enabled) {
            values.put(runtimeType, enabled);
        }
        @Override public boolean isEnabled(ai.chat2db.community.tools.enums.agent.AgentFeature feature) {
            return false;
        }
        @Override public void setEnabled(
                ai.chat2db.community.tools.enums.agent.AgentFeature feature, boolean enabled) {
        }
    }
}
