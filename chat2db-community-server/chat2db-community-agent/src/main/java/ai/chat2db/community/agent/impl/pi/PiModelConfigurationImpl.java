package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.agent.pi.IPiModelConfiguration;
import ai.chat2db.community.tools.agent.runtime.IAgentModelAccessProvider;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Owns the current model configuration and its temporary gateway access. */
public final class PiModelConfigurationImpl implements IPiModelConfiguration {

    private final String sessionId;
    private final Path directory;
    private final IAgentModelAccessProvider accessProvider;
    private final ObjectMapper json;
    private AgentModelAccess current;

    public PiModelConfigurationImpl(String sessionId, Path directory,
            IAgentModelAccessProvider accessProvider, ObjectMapper json) {
        this.sessionId = sessionId;
        this.directory = directory;
        this.accessProvider = accessProvider;
        this.json = json;
    }

    @Override
    public AgentModelAccess prepare(AgentModelSnapshot model) {
        AgentModelAccess next = accessProvider.issue(sessionId, model);
        try {
            write(next, model);
        } catch (IOException | RuntimeException error) {
            accessProvider.revoke(next.ticket());
            throw new PiRpcException("Cannot update the Pi model configuration", error);
        }
        if (current != null) accessProvider.revoke(current.ticket());
        current = next;
        return next;
    }

    @Override
    public void close() {
        if (current != null) {
            accessProvider.revoke(current.ticket());
            current = null;
        }
    }

    private void write(AgentModelAccess access, AgentModelSnapshot model) throws IOException {
        var modelNode = json.createObjectNode();
        modelNode.put("id", access.modelId());
        modelNode.put("name", access.modelId());
        modelNode.put("reasoning", "openai-responses".equals(access.api()));
        if (model.contextWindow() != null) modelNode.put("contextWindow", model.contextWindow());
        if (model.maxOutputTokens() != null) modelNode.put("maxTokens", model.maxOutputTokens());
        var provider = json.createObjectNode();
        provider.put("baseUrl", access.baseUrl());
        provider.put("api", access.api());
        provider.put("apiKey", access.ticket());
        provider.putObject("headers").put("X-Chat2DB-Model-Ticket", access.ticket());
        provider.putArray("models").add(modelNode);
        var root = json.createObjectNode();
        root.putObject("providers").set(access.provider(), provider);
        Path temporary = Files.createTempFile(directory, "models-", ".json.tmp");
        try {
            json.writeValue(temporary.toFile(), root);
            Files.move(temporary, directory.resolve("models.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }
}
