package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentModelAccessService;
import ai.chat2db.community.domain.api.service.agent.AgentToolAccessService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class PiRuntimeSessionLauncher implements PiSessionLauncher {

    private final PiProcessSupervisor supervisor;
    private final List<Path> extensions;
    private final ObjectMapper objectMapper;
    private final PiEventMapper eventMapper;
    private final AgentModelAccessService modelAccessService;
    private final AgentToolAccessService toolAccessService;

    public PiRuntimeSessionLauncher(
            PiProcessSupervisor supervisor,
            List<Path> extensions,
            AgentModelAccessService modelAccessService,
            AgentToolAccessService toolAccessService) {
        this(supervisor, extensions, modelAccessService, toolAccessService, new ObjectMapper(), new PiEventMapper());
    }

    PiRuntimeSessionLauncher(
            PiProcessSupervisor supervisor,
            List<Path> extensions,
            AgentModelAccessService modelAccessService,
            AgentToolAccessService toolAccessService,
            ObjectMapper objectMapper,
            PiEventMapper eventMapper) {
        this.supervisor = supervisor;
        this.extensions = List.copyOf(extensions);
        this.objectMapper = objectMapper;
        this.eventMapper = eventMapper;
        this.modelAccessService = modelAccessService;
        this.toolAccessService = toolAccessService;
    }

    @Override
    public IAgentRuntimeSessionHandle launch(
            String sessionId,
            String externalSessionId,
            String resumeReference,
            String systemPrompt,
            AgentModelSnapshot model,
            IAgentRuntimeEventSink eventSink) {
        AgentModelAccess modelAccess = modelAccessService.issue(sessionId, model);
        AgentToolAccess toolAccess = null;
        try {
            toolAccess = toolAccessService.issue(sessionId, eventSink);
            Path configuration = supervisor.prepareConfigurationDirectory(sessionId);
            writeModelConfiguration(configuration, modelAccess, model);
            objectMapper.writeValue(configuration.resolve("tools.json").toFile(), toolAccess);
            Path extension = configuration.resolve("chat2db-tools.mjs");
            try (var resource = new org.springframework.core.io.ClassPathResource("agent/chat2db-tools.mjs").getInputStream()) {
                Files.copy(resource, extension, StandardCopyOption.REPLACE_EXISTING);
            }
            List<Path> loadedExtensions = new ArrayList<>(extensions);
            loadedExtensions.add(extension);
            PiProcessHandle process = supervisor.start(
                    sessionId, externalSessionId, loadedExtensions, modelAccess, systemPrompt);
            AtomicReference<PiAgentRuntimeSessionHandle> handleReference = new AtomicReference<>();
            PiRpcClient rpc = new PiRpcClient(process.stdout(), process.stdin(), event -> {
                PiAgentRuntimeSessionHandle handle = handleReference.get();
                if (handle == null) {
                    throw new PiRpcException("Pi emitted an event before session initialization");
                }
                handle.accept(event);
            });
            String toolTicket = toolAccess.ticket();
            PiAgentRuntimeSessionHandle handle = new PiAgentRuntimeSessionHandle(
                    sessionId,
                    new AgentRuntimeSessionRef(externalSessionId, resumeReference),
                    process,
                    rpc,
                    eventMapper,
                    eventSink,
                    objectMapper,
                    () -> {
                        modelAccessService.revoke(modelAccess.ticket());
                        toolAccessService.revoke(toolTicket);
                    },
                    modelAccess.provider(),
                    modelAccess.modelId());
            handleReference.set(handle);
            return handle;
        } catch (IOException error) {
            modelAccessService.revoke(modelAccess.ticket());
            if (toolAccess != null) toolAccessService.revoke(toolAccess.ticket());
            throw new PiRpcException("Cannot start Pi runtime process", error);
        } catch (RuntimeException error) {
            modelAccessService.revoke(modelAccess.ticket());
            if (toolAccess != null) toolAccessService.revoke(toolAccess.ticket());
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
