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
import com.fasterxml.jackson.databind.JsonNode;
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
        PiProcessHandle process = null;
        PiRpcTransportImpl rpc = null;
        try {
            toolAccess = toolAccessService.issue(sessionId, eventSink);
            AtomicReference<AgentToolAccess> toolAccessRef = new AtomicReference<>(toolAccess);
            Path configuration = supervisor.prepareConfigurationDirectory(sessionId);
            modelConfiguration = new PiModelConfigurationImpl(sessionId, configuration, modelAccessService, objectMapper);
            AgentModelAccess modelAccess = modelConfiguration.prepare(model);
            writeToolAccess(configuration, objectMapper, toolAccess);
            Path extension = copyBundledExtensions(configuration);
            List<Path> loadedExtensions = new ArrayList<>(extensions);
            loadedExtensions.add(extension);
            process = supervisor.start(
                    sessionId, externalSessionId, loadedExtensions, modelAccess, systemPrompt, skills);
            AtomicReference<AgentRuntimeSessionHandleImpl> handleReference = new AtomicReference<>();
            Object eventLock = new Object();
            List<JsonNode> earlyEvents = new ArrayList<>();
            rpc = new PiRpcTransportImpl(process.stdout(), process.stdin(), event -> {
                synchronized (eventLock) {
                    AgentRuntimeSessionHandleImpl handle = handleReference.get();
                    if (handle == null) {
                        // The Pi process can emit its initial session event immediately after
                        // startup. Buffer it until the handle is fully wired so the transport
                        // reader cannot fail the whole session during this small startup window.
                        earlyEvents.add(event);
                        return;
                    }
                    handle.accept(event);
                }
            });
            AgentRuntimeSessionHandleImpl handle = new AgentRuntimeSessionHandleImpl(
                    sessionId,
                    new AgentRuntimeSessionRef(externalSessionId, resumeReference),
                    process,
                    rpc,
                    eventConverter,
                    eventSink,
                    objectMapper,
                    () -> toolAccessService.revoke(toolAccessRef.get().ticket()),
                    modelConfiguration,
                    () -> refreshToolAccess(sessionId, eventSink, configuration, toolAccessService,
                            objectMapper, toolAccessRef));
            synchronized (eventLock) {
                handleReference.set(handle);
                for (JsonNode event : earlyEvents) {
                    handle.accept(event);
                }
                earlyEvents.clear();
            }
            return handle;
        } catch (IOException error) {
            if (rpc != null) rpc.close();
            if (process != null) process.close();
            if (modelConfiguration != null) modelConfiguration.close();
            if (toolAccess != null) toolAccessService.revoke(toolAccess.ticket());
            throw new PiRpcException("Cannot start Pi runtime process", error);
        } catch (RuntimeException error) {
            if (rpc != null) rpc.close();
            if (process != null) process.close();
            if (modelConfiguration != null) modelConfiguration.close();
            if (toolAccess != null) toolAccessService.revoke(toolAccess.ticket());
            throw error;
        }
    }

    private void refreshToolAccess(
            String sessionId,
            IAgentRuntimeEventSink eventSink,
            Path configuration,
            IAgentToolAccessProvider provider,
            ObjectMapper mapper,
            AtomicReference<AgentToolAccess> current) {
        AgentToolAccess previous = current.get();
        AgentToolAccess next = provider.issue(sessionId, eventSink);
        try {
            writeToolAccess(configuration, mapper, next);
            current.set(next);
            provider.revoke(previous.ticket());
        } catch (IOException | RuntimeException error) {
            provider.revoke(next.ticket());
            throw error instanceof RuntimeException runtime
                    ? runtime : new PiRpcException("Cannot refresh Pi tool access", error);
        }
    }

    static Path copyBundledExtensions(Path configuration) throws IOException {
        for (String file : List.of("chat2db-tools.mjs", "chat2db-output.mjs")) {
            try (var resource = new ClassPathResource("/agent/" + file, PiSessionLauncherImpl.class).getInputStream()) {
                Files.copy(resource, configuration.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return configuration.resolve("chat2db-tools.mjs");
    }

    static void writeToolAccess(Path configuration, ObjectMapper mapper, AgentToolAccess access) throws IOException {
        Path temporary = Files.createTempFile(configuration, "tools-", ".json.tmp");
        try {
            mapper.writeValue(temporary.toFile(), access);
            Files.move(temporary, configuration.resolve("tools.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

}
