package ai.chat2db.community.domain.core.impl.task.pi;

import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.agent.PiRuntimeInstallTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PiRuntimeInstallTaskExecutorTest {
    private final List<Integer> progress = new ArrayList<>();
    private final TaskExecutionContext context = (TaskExecutionContext) Proxy.newProxyInstance(
            TaskExecutionContext.class.getClassLoader(), new Class<?>[] {TaskExecutionContext.class},
            (proxy, method, args) -> {
                assertEquals("reportProgress", method.getName());
                progress.add((Integer) args[0]);
                return null;
            });

    @Test
    void passesEnvironmentToInstallerAndReportsProgress() {
        var environment = new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64");
        var executor = new TaskExecutorImpl(request -> {
            assertEquals(environment, request);
            return Path.of("installed-runtime");
        });

        assertEquals("PI_RUNTIME_INSTALL", executor.taskType());
        assertEquals(PiRuntimeInstallTaskSpec.class, executor.specType());
        executor.execute(new PiRuntimeInstallTaskSpec(environment), context);

        assertEquals(List.of(5, 95), progress);
    }

    @Test
    void preservesInstallationFailureAndDoesNotReportCompletion() {
        var cause = new IOException("installation failed");
        var executor = new TaskExecutorImpl(request -> { throw cause; });
        var spec = new PiRuntimeInstallTaskSpec(new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64"));

        var failure = assertThrows(TaskExecutionException.class, () -> executor.execute(spec, context));

        assertSame(cause, failure.getCause());
        assertEquals(List.of(5), progress);
    }
}
