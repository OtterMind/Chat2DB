package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.service.agent.IAgentOutputDownloadService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentOutputService;
import ai.chat2db.community.tools.exception.storage.StorageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

public final class AgentOutputFileExport implements IAgentOutputDownloadService {
    private final IAiAgentOutputService outputs;
    private final Function<String, String> chooser;

    public AgentOutputFileExport(IAiAgentOutputService outputs, Function<String, String> chooser) {
        this.outputs = outputs;
        this.chooser = chooser;
    }

    @Override
    public String save(String sessionId, Long userId, String artifactId) {
        var reference = outputs.reference(sessionId, userId, artifactId);
        String selected = chooser.apply(artifactId + "." + reference.format());
        if (selected == null || selected.isBlank()) return null;
        Path temporary = null;
        try {
            Path selectedPath = Path.of(selected).toAbsolutePath().normalize();
            Path destination = selectedPath.getParent().toRealPath().resolve(selectedPath.getFileName());
            if (destination.startsWith(outputs.managedRoot().toRealPath()) || Files.isSymbolicLink(destination)) {
                throw new SecurityException("System output files are read-only");
            }
            temporary = Files.createTempFile(destination.getParent(), ".agent-output-", ".part");
            try (var stream = Files.newOutputStream(temporary)) {
                outputs.download(sessionId, userId, artifactId, stream);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination.toString();
        } catch (IOException error) {
            throw new StorageException("Cannot save the output file", error);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
