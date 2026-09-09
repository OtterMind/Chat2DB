package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionResumeRequest;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiAgentRuntimeAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesPiAndRoutesOpenAndResumeToTheLauncher() {
        RecordingLauncher launcher = new RecordingLauncher();
        PiAgentRuntimeAdapter adapter = new PiAgentRuntimeAdapter(
                "0.85.1", "rpc-v1",
                new PiRuntimeEnvironmentChecker(new PiRuntimeLayout(temporaryDirectory, "0.85.1")),
                launcher, () -> true);

        adapter.openSession(new AgentRuntimeSessionOpenRequest(
                "session", "external", null, model()), event -> { });
        assertEquals("session", launcher.sessionId);
        assertEquals(null, launcher.resumeReference);

        adapter.resumeSession(new AgentRuntimeSessionResumeRequest(
                "session", new AgentRuntimeBinding(
                        AgentRuntimeType.PI, "0.85.1", "rpc-v1", "external", "resume", 1), model()),
                event -> { });
        assertEquals("resume", launcher.resumeReference);
        assertEquals(AgentRuntimeType.PI, adapter.descriptor().type());
        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED,
                adapter.inspectEnvironment(new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64")).status());
    }

    @Test
    void blocksInspectionAndOpeningWhileBetaIsDisabled() {
        PiAgentRuntimeAdapter adapter = new PiAgentRuntimeAdapter(
                "0.85.1", "rpc-v1",
                new PiRuntimeEnvironmentChecker(new PiRuntimeLayout(temporaryDirectory, "0.85.1")),
                new RecordingLauncher(), () -> false);

        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED,
                adapter.inspectEnvironment(new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64")).status());
        assertThrows(PiRpcException.class, () -> adapter.openSession(
                new AgentRuntimeSessionOpenRequest("session", "external", null, model()), event -> { }));
    }

    private AgentModelSnapshot model() {
        return new AgentModelSnapshot("model", 1, "openai", "gpt", 1000, 100);
    }

    private static final class RecordingLauncher implements PiSessionLauncher {
        private String sessionId;
        private String resumeReference;
        @Override
        public AgentRuntimeSessionHandle launch(
                String sessionId,
                String externalSessionId,
                String resumeReference,
                AgentModelSnapshot model,
                ai.chat2db.community.domain.api.service.agent.AgentRuntimeEventSink eventSink) {
            this.sessionId = sessionId;
            this.resumeReference = resumeReference;
            return null;
        }
    }
}
