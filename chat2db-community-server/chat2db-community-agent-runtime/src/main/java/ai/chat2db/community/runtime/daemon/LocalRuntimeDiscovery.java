package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class LocalRuntimeDiscovery {

    static final String CODEX_PATH_ENV = "CHAT2DB_CODEX_PATH";
    static final String HERMES_PATH_ENV = "CHAT2DB_HERMES_PATH";
    static final String DSH_PATH_ENV = "CHAT2DB_DSH_PATH";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_VERSION_LENGTH = 128;

    private final Map<String, String> environment;
    private final Path userHome;

    public LocalRuntimeDiscovery() {
        this(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    LocalRuntimeDiscovery(Map<String, String> environment, Path userHome) {
        this.environment = environment;
        this.userHome = userHome.toAbsolutePath().normalize();
    }

    public List<LocalRuntimeInstallation> discover(Set<AgentRuntimeProviderEnum> requestedProviders) {
        List<LocalRuntimeInstallation> result = new ArrayList<>();
        for (AgentRuntimeProviderEnum provider : ExternalRuntimeProviderCatalog.providers()) {
            if (requestedProviders != null && !requestedProviders.contains(provider)) {
                continue;
            }
            Path executable = resolveExecutable(provider);
            if (executable == null) {
                continue;
            }
            String version = probeVersion(provider, executable);
            if (version != null) {
                result.add(new LocalRuntimeInstallation(provider, executable, version));
            }
        }
        return List.copyOf(result);
    }

    Path resolveExecutable(AgentRuntimeProviderEnum provider) {
        String command = command(provider);
        String override = trim(environment.get(pathEnvironment(provider)));
        if (override != null) {
            return executable(Path.of(override));
        }
        Path fromPath = findOnPath(command, environment.get("PATH"));
        if (fromPath != null) {
            return fromPath;
        }
        Path fromShell = findFromLoginShell(command);
        if (fromShell != null) {
            return fromShell;
        }
        for (Path candidate : fallbackCandidates(provider)) {
            Path resolved = executable(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    String probeVersion(AgentRuntimeProviderEnum provider, Path executable) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(ExternalRuntimeProviderCatalog.versionArguments(provider));
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) {
                return null;
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            if (firstLine.isBlank()) {
                return null;
            }
            return firstLine.substring(0, Math.min(firstLine.length(), MAX_VERSION_LENGTH));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Path findOnPath(String command, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        for (String entry : pathValue.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                Path candidate = executable(Path.of(entry).resolve(command));
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Path findFromLoginShell(String command) {
        String shellValue = trim(environment.get("SHELL"));
        if (shellValue == null) {
            return null;
        }
        Path shell = Path.of(shellValue);
        if (!Set.of("bash", "zsh", "sh", "dash", "ksh").contains(shell.getFileName().toString())) {
            return null;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(shell.toString(), "-ilc",
                    "unalias " + command + " >/dev/null 2>&1 || true; "
                            + "unset -f " + command + " >/dev/null 2>&1 || true; command -v " + command)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) {
                return null;
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            return firstLine.startsWith("/") ? executable(Path.of(firstLine)) : null;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<Path> fallbackCandidates(AgentRuntimeProviderEnum provider) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        if (provider == AgentRuntimeProviderEnum.CODEX
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            candidates.add(Path.of("/Applications/ChatGPT.app/Contents/Resources/codex"));
            candidates.add(Path.of("/Applications/Codex.app/Contents/Resources/codex"));
            candidates.add(userHome.resolve("Applications/ChatGPT.app/Contents/Resources/codex"));
            candidates.add(userHome.resolve("Applications/Codex.app/Contents/Resources/codex"));
        }
        if (provider == AgentRuntimeProviderEnum.HERMES) {
            candidates.add(userHome.resolve(".local/bin/hermes"));
        }
        if (provider == AgentRuntimeProviderEnum.DSH) {
            candidates.add(userHome.resolve(".local/bin/dsh"));
        }
        return List.copyOf(candidates);
    }

    private Path executable(Path candidate) {
        try {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
                return null;
            }
            return normalized.toRealPath();
        } catch (IOException exception) {
            return null;
        }
    }

    private String pathEnvironment(AgentRuntimeProviderEnum provider) {
        return switch (provider) {
            case CODEX -> CODEX_PATH_ENV;
            case HERMES -> HERMES_PATH_ENV;
            case DSH -> DSH_PATH_ENV;
            case SPRING_AI -> throw new IllegalArgumentException("Spring AI has no local executable");
        };
    }

    private String command(AgentRuntimeProviderEnum provider) {
        String command = provider.defaultExecutable();
        if (command == null) {
            throw new IllegalArgumentException("Spring AI has no local executable");
        }
        return command;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
