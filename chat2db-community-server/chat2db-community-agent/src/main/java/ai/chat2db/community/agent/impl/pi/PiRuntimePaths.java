package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.tools.util.ConfigUtils;
import java.nio.file.Path;

public class PiRuntimePaths {

    private final Path root;

    public PiRuntimePaths() {
        this(Path.of(ConfigUtils.getBasePath()).resolve("runtime/agent/pi"));
    }

    PiRuntimePaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path installations() {
        return root;
    }

    public Path temporary() {
        return root.resolve("tmp");
    }
}
