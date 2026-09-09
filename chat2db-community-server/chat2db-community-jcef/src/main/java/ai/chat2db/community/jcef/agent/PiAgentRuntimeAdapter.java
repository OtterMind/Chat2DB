package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapabilities;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCapability;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionDeleteRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionResumeRequest;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeAdapter;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeEventSink;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;

import java.util.Set;

public class PiAgentRuntimeAdapter implements AgentRuntimeAdapter {

    private final AgentRuntimeDescriptor descriptor;
    private final PiRuntimeEnvironmentChecker environmentChecker;
    private final PiSessionLauncher sessionLauncher;

    public PiAgentRuntimeAdapter(
            String version,
            String protocolVersion,
            PiRuntimeEnvironmentChecker environmentChecker,
            PiSessionLauncher sessionLauncher) {
        this.descriptor = new AgentRuntimeDescriptor(
                AgentRuntimeType.PI,
                "Pi",
                version,
                protocolVersion,
                new AgentRuntimeCapabilities(Set.of(
                        AgentRuntimeCapability.STREAMING,
                        AgentRuntimeCapability.CANCELLATION,
                        AgentRuntimeCapability.USAGE,
                        AgentRuntimeCapability.COMPACTION,
                        AgentRuntimeCapability.STRUCTURED_INTERACTION), 1));
        this.environmentChecker = environmentChecker;
        this.sessionLauncher = sessionLauncher;
    }

    @Override
    public AgentRuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AgentRuntimeEnvironmentReport inspectEnvironment(AgentRuntimeEnvironmentRequest request) {
        return environmentChecker.inspect(request);
    }

    @Override
    public AgentRuntimeSessionHandle openSession(
            AgentRuntimeSessionOpenRequest request,
            AgentRuntimeEventSink eventSink) {
        return sessionLauncher.launch(
                request.sessionId(), request.externalSessionId(), null, eventSink);
    }

    @Override
    public AgentRuntimeSessionHandle resumeSession(
            AgentRuntimeSessionResumeRequest request,
            AgentRuntimeEventSink eventSink) {
        return sessionLauncher.launch(
                request.sessionId(), request.binding().externalSessionId(),
                request.binding().resumeReference(), eventSink);
    }

    @Override
    public void deleteSession(AgentRuntimeSessionDeleteRequest request) {
        // Product storage owns V2 session deletion; closing the registered handle stops Pi first.
    }
}
