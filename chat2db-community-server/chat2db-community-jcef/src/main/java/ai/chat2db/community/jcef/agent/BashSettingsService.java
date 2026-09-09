package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentShellSettings;
import ai.chat2db.community.domain.api.service.agent.AgentShellSettingsService;
import ai.chat2db.community.domain.api.service.agent.AgentShellSettingsStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.AgentTrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

public class BashSettingsService implements AgentShellSettingsService {
    private final AgentShellSettingsStorage storage;
    private final Path defaultWorkspaces;

    public BashSettingsService(AgentShellSettingsStorage storage, Path defaultWorkspaces) {
        this.storage = storage;
        this.defaultWorkspaces = defaultWorkspaces.toAbsolutePath().normalize();
    }

    @Override
    public AgentShellSettings get() {
        return new AgentShellSettings(storage.getWorkingDirectory());
    }

    @Override
    public AgentShellSettings update(String workingDirectory) {
        String value = workingDirectory.strip();
        String directory = value.isEmpty() ? "" : existingDirectory(value).toString();
        storage.setWorkingDirectory(directory);
        AgentTrace.record("shell.settings.saved", null, null, Map.of("workingDirectory", directory));
        return new AgentShellSettings(directory);
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
