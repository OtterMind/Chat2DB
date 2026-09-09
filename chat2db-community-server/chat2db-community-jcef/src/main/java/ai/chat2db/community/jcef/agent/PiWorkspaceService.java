package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentWorkspaceSettings;
import ai.chat2db.community.domain.api.service.agent.AgentWorkspaceService;
import ai.chat2db.community.domain.api.service.agent.AgentWorkspaceStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.AgentTrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

public class PiWorkspaceService implements AgentWorkspaceService {
    private final AgentWorkspaceStorage storage;
    private final Path defaultWorkspaces;

    public PiWorkspaceService(AgentWorkspaceStorage storage, Path defaultWorkspaces) {
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
            throw new BusinessException("agent.bash.directory.invalid");
        }
    }

    @Override
    public ai.chat2db.community.domain.api.model.agent.AgentDirectoryListing listDirectories(String path) {
        Path directory = existingDirectory(path.isBlank() ? System.getProperty("user.home") : path);
        try (var children = Files.list(directory)) {
            var entries = children.filter(Files::isDirectory).filter(Files::isReadable)
                    .sorted(java.util.Comparator.comparing(item -> item.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(item -> new ai.chat2db.community.domain.api.model.agent.AgentDirectoryListing.Entry(
                            item.getFileName().toString(), item.toString())).toList();
            return new ai.chat2db.community.domain.api.model.agent.AgentDirectoryListing(directory.toString(),
                    directory.getParent() == null ? null : directory.getParent().toString(), entries);
        } catch (IOException error) {
            throw new BusinessException("agent.bash.directory.invalid");
        }
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
            throw new BusinessException("agent.bash.directory.invalid");
        }
    }
}
