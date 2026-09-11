package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.agent.pi.IPiSessionLauncher;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeBinding;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionResumeRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeAdapterImplTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesPiAndRoutesOpenAndResumeToTheLauncher() {
        RecordingLauncher launcher = new RecordingLauncher();
        AgentRuntimeAdapterImpl adapter = new AgentRuntimeAdapterImpl(
                "0.85.1", "rpc-v1",
                new AgentRuntimeEnvironmentCheckerImpl(new PiRuntimeLayout(temporaryDirectory, "0.85.1")),
                launcher, () -> true);

        adapter.openSession(new AgentRuntimeSessionOpenRequest(
                "session", "external", "existing V1 prompt", model()), event -> { });
        assertEquals("session", launcher.sessionId);
        assertEquals("existing V1 prompt", launcher.systemPrompt);
        assertEquals(null, launcher.resumeReference);

        adapter.resumeSession(new AgentRuntimeSessionResumeRequest(
                "session", new AgentRuntimeBinding(
                        AgentRuntimeType.PI, "0.85.1", "rpc-v1", "external", "resume", 1), "existing V1 prompt", model()),
                event -> { });
        assertEquals("resume", launcher.resumeReference);
        assertEquals("existing V1 prompt", launcher.systemPrompt);
        assertEquals(AgentRuntimeType.PI, adapter.descriptor().type());
        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED,
                adapter.inspectEnvironment(new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64")).status());
    }

    @Test
    void blocksInspectionAndOpeningWhileBetaIsDisabled() {
        AgentRuntimeAdapterImpl adapter = new AgentRuntimeAdapterImpl(
                "0.85.1", "rpc-v1",
                new AgentRuntimeEnvironmentCheckerImpl(new PiRuntimeLayout(temporaryDirectory, "0.85.1")),
                new RecordingLauncher(), () -> false);

        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED,
                adapter.inspectEnvironment(new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64")).status());
        assertThrows(PiRpcException.class, () -> adapter.openSession(
                new AgentRuntimeSessionOpenRequest("session", "external", null, model()), event -> { }));
    }

    private AgentModelSnapshot model() {
        return new AgentModelSnapshot("model", 1, "openai", "gpt", 1000, 100);
    }

    private static final class RecordingLauncher implements IPiSessionLauncher {
        private String sessionId;
        private String resumeReference;
        private String systemPrompt;
        @Override
        public IAgentRuntimeSessionHandle launch(
                String sessionId,
                String externalSessionId,
                String resumeReference,
                String systemPrompt,
                AgentModelSnapshot model,
                ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink eventSink) {
            this.sessionId = sessionId;
            this.resumeReference = resumeReference;
            this.systemPrompt = systemPrompt;
            return null;
        }
    }
}
