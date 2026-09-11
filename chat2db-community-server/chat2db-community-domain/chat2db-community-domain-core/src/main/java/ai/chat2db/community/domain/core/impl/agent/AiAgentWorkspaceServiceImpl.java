package ai.chat2db.community.domain.core.impl.agent;


import ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings;
import ai.chat2db.community.domain.api.service.agent.IAgentWorkspaceStorage;
import ai.chat2db.community.domain.api.service.agent.IAiAgentWorkspaceService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.AgentTrace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

public class AiAgentWorkspaceServiceImpl implements IAiAgentWorkspaceService {
    private final IAgentWorkspaceStorage storage;
    private final Path defaultWorkspaces;
    private final java.util.function.Supplier<String> directoryChooser;

    public AiAgentWorkspaceServiceImpl(IAgentWorkspaceStorage storage, Path defaultWorkspaces,
            java.util.function.Supplier<String> directoryChooser) {
        this.directoryChooser = directoryChooser;
        this.storage = storage;
        this.defaultWorkspaces = defaultWorkspaces.toAbsolutePath().normalize();
    }

    @Override
    public AgentWorkspaceSettings get() {
        return new AgentWorkspaceSettings(storage.getWorkingDirectory());
    }

    @Override
    public AgentWorkspaceSettings update(String workingDirectory) {
        String value = workingDirectory.strip();
        String directory = value.isEmpty() ? "" : existingDirectory(value).toString();
        storage.setWorkingDirectory(directory);
        AgentTrace.record("workspace.settings.saved", null, null, Map.of("workingDirectory", directory));
        return new AgentWorkspaceSettings(directory);
    }

    @Override
    public String resolveWorkingDirectory(String sessionId) {
        if (!sessionId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid session id");
        String selected = storage.getWorkingDirectory();
        if (!selected.isEmpty()) return existingDirectory(selected).toString();
        Path workspace = defaultWorkspaces.resolve(sessionId);
        try {
            Files.createDirectories(workspace);
            if (Files.isSymbolicLink(workspace)
                    || !workspace.toRealPath().startsWith(defaultWorkspaces.toRealPath())) {
                throw new BusinessException("agent.bash.directory.invalid");
            }
            return workspace.toRealPath().toString();
        } catch (IOException error) {
            throw new BusinessException("agent.bash.directory.invalid", null, error);
        }
    }

    @Override
    public String selectDirectory() {
        AgentTrace.record("workspace.picker.opened", null, null, Map.of());
        String selected = directoryChooser.get();
        String path = selected == null ? null : existingDirectory(selected).toString();
        AgentTrace.record("workspace.picker.closed", null, null, Map.of("selected", path != null));
        return path;
    }

    @Override
    public boolean isToolEnabled(String toolName) {
        return ai.chat2db.community.tools.util.agent.AgentNativeTools.currentPlatform().contains(toolName)
                && storage.isToolEnabled(toolName);
    }

    @Override
    public void setToolEnabled(String toolName, boolean enabled) {
        if (!ai.chat2db.community.tools.util.agent.AgentNativeTools.currentPlatform().contains(toolName)) {
            throw new IllegalArgumentException("Tool is unavailable on this platform");
        }
        storage.setToolEnabled(toolName, enabled);
        AgentTrace.record("tool.settings.saved", null, null, Map.of("tool", toolName, "enabled", enabled));
    }

    static Path existingDirectory(String value) {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) throw new BusinessException("agent.bash.directory.absolute");
            Path directory = path.toRealPath();
            if (!Files.isDirectory(directory) || !Files.isReadable(directory)) {
                throw new BusinessException("agent.bash.directory.invalid");
            }
            return directory;
        } catch (IOException | InvalidPathException error) {
            throw new BusinessException("agent.bash.directory.invalid", null, error);
        }
    }
}
