package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentFeature;
import ai.chat2db.community.domain.api.model.agent.AgentFeatureState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class BashEnvironmentChecker {

    private final Supplier<String> operatingSystem;
    private final Predicate<Path> executable;
    private final BooleanSupplier windowsSandboxAvailable;

    public BashEnvironmentChecker() {
        this(() -> System.getProperty("os.name", "unknown"), Files::isExecutable, () -> false);
    }

    BashEnvironmentChecker(
            Supplier<String> operatingSystem,
            Predicate<Path> executable,
            BooleanSupplier windowsSandboxAvailable) {
        this.operatingSystem = operatingSystem;
        this.executable = executable;
        this.windowsSandboxAvailable = windowsSandboxAvailable;
    }

    public AgentFeatureState check(boolean enabled) {
        String os = operatingSystem.get().toLowerCase(Locale.ROOT);
        List<String> checks = new ArrayList<>();
        Map<String, String> diagnostics = new LinkedHashMap<>();
        Path shell;
        Path sandbox;
        if (os.contains("win")) {
            shell = firstExecutable(List.of(
                    Path.of("C:/Program Files/Git/bin/bash.exe"),
                    Path.of("C:/Program Files/Git/usr/bin/bash.exe")));
            sandbox = null;
            if (shell != null) {
                checks.add("GIT_BASH_FOUND");
            }
            if (windowsSandboxAvailable.getAsBoolean()) {
                checks.add("WINDOWS_PROCESS_SANDBOX_READY");
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            shell = executable.test(Path.of("/bin/bash")) ? Path.of("/bin/bash") : null;
            sandbox = executable.test(Path.of("/usr/bin/sandbox-exec"))
                    ? Path.of("/usr/bin/sandbox-exec") : null;
        } else if (os.contains("linux")) {
            shell = executable.test(Path.of("/bin/bash")) ? Path.of("/bin/bash") : null;
            sandbox = executable.test(Path.of("/usr/bin/bwrap")) ? Path.of("/usr/bin/bwrap") : null;
        } else {
            shell = null;
            sandbox = null;
        }
        if (shell != null && !os.contains("win")) {
            checks.add("BASH_FOUND");
        }
        boolean sandboxReady = os.contains("win") ? windowsSandboxAvailable.getAsBoolean() : sandbox != null;
        if (sandbox != null) {
            checks.add("PROCESS_SANDBOX_FOUND");
        }
        boolean available = shell != null && sandboxReady;
        if (shell == null) {
            diagnostics.put("shell", os.contains("win") ? "Git Bash was not found" : "/bin/bash was not found");
        }
        if (!sandboxReady) {
            diagnostics.put("sandbox", "A supported process sandbox was not found");
        }
        return new AgentFeatureState(AgentFeature.BASH, enabled && available, available, checks, diagnostics);
    }

    private Path firstExecutable(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (executable.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
