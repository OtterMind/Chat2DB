package ai.chat2db.community.runtime.workspace;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

public class TaskWorkspaceManager {

    private final Path root;

    public TaskWorkspaceManager(Path root) {
        if (root == null || !root.isAbsolute()) {
            throw new IllegalArgumentException("Runtime workspace root must be absolute");
        }
        this.root = root.normalize();
    }

    public Path root() {
        return root;
    }

    public Path create(String runId, int leaseAttempt) {
        if (StringUtils.isBlank(runId) || leaseAttempt <= 0) {
            throw new IllegalArgumentException("Runtime workspace requires Run id and positive lease attempt");
        }
        String safeRunId = runId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        Path workspace = root.resolve("run-" + safeRunId + "-attempt-" + leaseAttempt).normalize();
        requireChild(workspace);
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Runtime workspace root may not be a symbolic link");
            }
            Files.createDirectory(workspace);
            return workspace;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create isolated Runtime workspace", exception);
        }
    }

    public void cleanup(Path workspace) {
        if (workspace == null) {
            return;
        }
        Path normalized = workspace.toAbsolutePath().normalize();
        requireChild(normalized);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clean isolated Runtime workspace", exception);
        }
    }

    private void requireChild(Path candidate) {
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new SecurityException("Runtime workspace is outside the configured root");
        }
    }
}
