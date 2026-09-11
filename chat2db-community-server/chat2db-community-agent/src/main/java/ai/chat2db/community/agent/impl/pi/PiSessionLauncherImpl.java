package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.converter.pi.PiEventConverter;
import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.agent.pi.IPiModelConfiguration;
import ai.chat2db.community.agent.pi.IPiSessionLauncher;
import ai.chat2db.community.tools.agent.runtime.IAgentModelAccessProvider;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.agent.runtime.IAgentToolAccessProvider;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill;
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
import org.springframework.core.io.ClassPathResource;

public class PiSessionLauncherImpl implements IPiSessionLauncher {

    private final PiProcessSupervisor supervisor;
    private final List<Path> extensions;
    private final ObjectMapper objectMapper;
    private final PiEventConverter eventConverter;
    private final IAgentModelAccessProvider modelAccessService;
    private final IAgentToolAccessProvider toolAccessService;

    public PiSessionLauncherImpl(
            PiProcessSupervisor supervisor,
            List<Path> extensions,
            IAgentModelAccessProvider modelAccessService,
            IAgentToolAccessProvider toolAccessService) {
        this(supervisor, extensions, modelAccessService, toolAccessService, new ObjectMapper(), new PiEventConverter());
    }

    PiSessionLauncherImpl(
            PiProcessSupervisor supervisor,
            List<Path> extensions,
            IAgentModelAccessProvider modelAccessService,
            IAgentToolAccessProvider toolAccessService,
            ObjectMapper objectMapper,
            PiEventConverter eventConverter) {
        this.supervisor = supervisor;
        this.extensions = List.copyOf(extensions);
        this.objectMapper = objectMapper;
        this.eventConverter = eventConverter;
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
            List<AgentRuntimeSkill> skills,
            IAgentRuntimeEventSink eventSink) {
        IPiModelConfiguration modelConfiguration = null;
        AgentToolAccess toolAccess = null;
        try {
            toolAccess = toolAccessService.issue(sessionId, eventSink);
            Path configuration = supervisor.prepareConfigurationDirectory(sessionId);
            modelConfiguration = new PiModelConfigurationImpl(sessionId, configuration, modelAccessService, objectMapper);
            AgentModelAccess modelAccess = modelConfiguration.prepare(model);
            objectMapper.writeValue(configuration.resolve("tools.json").toFile(), toolAccess);
            Path extension = configuration.resolve("chat2db-tools.mjs");
            try (var resource = new ClassPathResource("agent/chat2db-tools.mjs").getInputStream()) {
                Files.copy(resource, extension, StandardCopyOption.REPLACE_EXISTING);
            }
            List<Path> loadedExtensions = new ArrayList<>(extensions);
            loadedExtensions.add(extension);
            PiProcessHandle process = supervisor.start(
                    sessionId, externalSessionId, loadedExtensions, modelAccess, systemPrompt, skills);
            AtomicReference<AgentRuntimeSessionHandleImpl> handleReference = new AtomicReference<>();
            PiRpcTransportImpl rpc = new PiRpcTransportImpl(process.stdout(), process.stdin(), event -> {
                AgentRuntimeSessionHandleImpl handle = handleReference.get();
                if (handle == null) {
                    throw new PiRpcException("Pi emitted an event before session initialization");
                }
                handle.accept(event);
            });
            String toolTicket = toolAccess.ticket();
            AgentRuntimeSessionHandleImpl handle = new AgentRuntimeSessionHandleImpl(
                    sessionId,
                    new AgentRuntimeSessionRef(externalSessionId, resumeReference),
                    process,
                    rpc,
                    eventConverter,
                    eventSink,
                    objectMapper,
                    () -> toolAccessService.revoke(toolTicket),
                    modelConfiguration);
            handleReference.set(handle);
            return handle;
        } catch (IOException error) {
            if (modelConfiguration != null) modelConfiguration.close();
            if (toolAccess != null) toolAccessService.revoke(toolAccess.ticket());
            throw new PiRpcException("Cannot start Pi runtime process", error);
        } catch (RuntimeException error) {
            if (modelConfiguration != null) modelConfiguration.close();
            if (toolAccess != null) toolAccessService.revoke(toolAccess.ticket());
            throw error;
        }
    }

}
