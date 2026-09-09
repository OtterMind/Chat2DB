package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeEventSink;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class PiRuntimeSessionLauncher implements PiSessionLauncher {

    private final PiProcessSupervisor supervisor;
    private final List<Path> extensions;
    private final ObjectMapper objectMapper;
    private final PiEventMapper eventMapper;

    public PiRuntimeSessionLauncher(PiProcessSupervisor supervisor, List<Path> extensions) {
        this(supervisor, extensions, new ObjectMapper(), new PiEventMapper());
    }

    PiRuntimeSessionLauncher(
            PiProcessSupervisor supervisor,
            List<Path> extensions,
            ObjectMapper objectMapper,
            PiEventMapper eventMapper) {
        this.supervisor = supervisor;
        this.extensions = List.copyOf(extensions);
        this.objectMapper = objectMapper;
        this.eventMapper = eventMapper;
    }

    @Override
    public AgentRuntimeSessionHandle launch(
            String sessionId,
            String externalSessionId,
            String resumeReference,
            AgentRuntimeEventSink eventSink) {
        try {
            PiProcessHandle process = supervisor.start(sessionId, externalSessionId, extensions);
            AtomicReference<PiAgentRuntimeSessionHandle> handleReference = new AtomicReference<>();
            PiRpcClient rpc = new PiRpcClient(process.stdout(), process.stdin(), event -> {
                PiAgentRuntimeSessionHandle handle = handleReference.get();
                if (handle == null) {
                    throw new PiRpcException("Pi emitted an event before session initialization");
                }
                handle.accept(event);
            });
            PiAgentRuntimeSessionHandle handle = new PiAgentRuntimeSessionHandle(
                    sessionId,
                    new AgentRuntimeSessionRef(externalSessionId, resumeReference),
                    process,
                    rpc,
                    eventMapper,
                    eventSink,
                    objectMapper);
            handleReference.set(handle);
            return handle;
        } catch (IOException error) {
            throw new PiRpcException("Cannot start Pi runtime process", error);
        }
    }
}
