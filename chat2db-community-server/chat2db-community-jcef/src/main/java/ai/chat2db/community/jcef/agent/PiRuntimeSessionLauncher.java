package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.service.agent.AgentModelAccessService;
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
    private final AgentModelAccessService modelAccessService;

    public PiRuntimeSessionLauncher(
            PiProcessSupervisor supervisor,
            List<Path> extensions,
            AgentModelAccessService modelAccessService) {
        this(supervisor, extensions, modelAccessService, new ObjectMapper(), new PiEventMapper());
    }

    PiRuntimeSessionLauncher(
            PiProcessSupervisor supervisor,
            List<Path> extensions,
            AgentModelAccessService modelAccessService,
            ObjectMapper objectMapper,
            PiEventMapper eventMapper) {
        this.supervisor = supervisor;
        this.extensions = List.copyOf(extensions);
        this.objectMapper = objectMapper;
        this.eventMapper = eventMapper;
        this.modelAccessService = modelAccessService;
    }

    @Override
    public AgentRuntimeSessionHandle launch(
            String sessionId,
            String externalSessionId,
            String resumeReference,
            AgentModelSnapshot model,
            AgentRuntimeEventSink eventSink) {
        AgentModelAccess modelAccess = modelAccessService.issue(sessionId, model);
        try {
            writeModelConfiguration(supervisor.prepareConfigurationDirectory(sessionId), modelAccess, model);
            PiProcessHandle process = supervisor.start(sessionId, externalSessionId, extensions, modelAccess);
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
                    objectMapper,
                    () -> modelAccessService.revoke(modelAccess.ticket()),
                    modelAccess.provider(),
                    modelAccess.modelId());
            handleReference.set(handle);
            return handle;
        } catch (IOException error) {
            modelAccessService.revoke(modelAccess.ticket());
            throw new PiRpcException("Cannot start Pi runtime process", error);
        } catch (RuntimeException error) {
            modelAccessService.revoke(modelAccess.ticket());
            throw error;
        }
    }

    private void writeModelConfiguration(
            Path configurationDirectory,
            AgentModelAccess access,
            AgentModelSnapshot model) throws IOException {
        var modelNode = objectMapper.createObjectNode();
        modelNode.put("id", access.modelId());
        modelNode.put("name", access.modelId());
        modelNode.put("reasoning", true);
        if (model.contextWindow() != null) {
            modelNode.put("contextWindow", model.contextWindow());
        }
        if (model.maxOutputTokens() != null) {
            modelNode.put("maxTokens", model.maxOutputTokens());
        }
        var provider = objectMapper.createObjectNode();
        provider.put("baseUrl", access.baseUrl());
        provider.put("api", access.api());
        provider.put("apiKey", "$CHAT2DB_MODEL_TICKET");
        provider.putArray("models").add(modelNode);
        var root = objectMapper.createObjectNode();
        root.putObject("providers").set(access.provider(), provider);
        objectMapper.writeValue(configurationDirectory.resolve("models.json").toFile(), root);
    }
}
