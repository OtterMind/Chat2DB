package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class LocalRuntimeDiscovery {

    static final String CODEX_PATH_ENV = "CHAT2DB_CODEX_PATH";
    static final String CLAUDE_CODE_PATH_ENV = "CHAT2DB_CLAUDE_CODE_PATH";
    static final String OPENCODE_PATH_ENV = "CHAT2DB_OPENCODE_PATH";
    static final String PI_PATH_ENV = "CHAT2DB_PI_PATH";
    static final String HERMES_PATH_ENV = "CHAT2DB_HERMES_PATH";
    static final String DSH_PATH_ENV = "CHAT2DB_DSH_PATH";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_VERSION_LENGTH = 128;
    private static final String EXECUTABLE_MARKER = "__CHAT2DB_EXECUTABLE__";
    private static final String PATH_MARKER = "__CHAT2DB_PATH__";
    private static final System.Logger LOG = System.getLogger(LocalRuntimeDiscovery.class.getName());

    private final Map<String, String> environment;
    private final Path userHome;
    private final String operatingSystem;

    public LocalRuntimeDiscovery() {
        this(System.getenv(), Path.of(System.getProperty("user.home")), System.getProperty("os.name", ""));
    }

    LocalRuntimeDiscovery(Map<String, String> environment, Path userHome) {
        this(environment, userHome, System.getProperty("os.name", ""));
    }

    LocalRuntimeDiscovery(Map<String, String> environment, Path userHome, String operatingSystem) {
        this.operatingSystem = operatingSystem == null ? "" : operatingSystem;
        LinkedHashMap<String, String> normalizedEnvironment = new LinkedHashMap<>();
        environment.forEach((name, value) -> normalizedEnvironment.put(
                isWindows() ? name.toUpperCase(Locale.ROOT) : name, value));
        this.environment = Map.copyOf(normalizedEnvironment);
        this.userHome = userHome.toAbsolutePath().normalize();
    }

    public List<LocalRuntimeInstallation> discover(Set<AgentRuntimeProviderEnum> requestedProviders) {
        List<LocalRuntimeInstallation> result = new ArrayList<>();
        for (AgentRuntimeProviderEnum provider : ExternalRuntimeProviderCatalog.providers()) {
            if (requestedProviders != null && !requestedProviders.contains(provider)) {
                continue;
            }
            LocalRuntimeInstallation installation = discover(provider);
            if (installation != null) {
                result.add(installation);
            }
        }
        return List.copyOf(result);
    }

    private LocalRuntimeInstallation discover(AgentRuntimeProviderEnum provider) {
        List<ExecutableCandidate> candidates = executableCandidates(provider);
        for (ExecutableCandidate candidate : candidates) {
            String version = probeVersion(provider, candidate.executable(), candidate.environment());
            if (version != null) {
                LOG.log(System.Logger.Level.INFO, "Detected local {0} Runtime {1} at {2} via {3}",
                        provider.name(), version, candidate.executable(), candidate.source());
                return new LocalRuntimeInstallation(provider, candidate.executable(), version, candidate.environment());
            }
        }
        LOG.log(System.Logger.Level.INFO, "No usable local {0} Runtime was detected from {1} candidate(s)",
                provider.name(), candidates.size());
        return null;
    }

    Path resolveExecutable(AgentRuntimeProviderEnum provider) {
        return executableCandidates(provider).stream().findFirst().map(ExecutableCandidate::executable).orElse(null);
    }

    String probeVersion(AgentRuntimeProviderEnum provider, Path executable) {
        return probeVersion(provider, executable, Map.of());
    }

    private String probeVersion(AgentRuntimeProviderEnum provider, Path executable,
                                Map<String, String> candidateEnvironment) {
        List<String> command = windowsCommand(executable, ExternalRuntimeProviderCatalog.versionArguments(provider));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().putAll(candidateEnvironment);
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.log(System.Logger.Level.WARNING, "Runtime version probe timed out for {0} at {1}",
                        provider.name(), executable);
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) {
                LOG.log(System.Logger.Level.WARNING, "Runtime version probe failed for {0} at {1} with exit code {2}",
                        provider.name(), executable, process.exitValue());
                return null;
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            return firstLine.isBlank() ? null
                    : firstLine.substring(0, Math.min(firstLine.length(), MAX_VERSION_LENGTH));
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.WARNING, "Runtime version probe could not start for "
                    + provider.name() + " at " + executable + ": " + exception.getMessage());
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<ExecutableCandidate> executableCandidates(AgentRuntimeProviderEnum provider) {
        String command = command(provider);
        String override = trim(environment.get(pathEnvironment(provider)));
        if (override != null) {
            List<ExecutableCandidate> configured = commandCandidates(Path.of(unquote(override))).stream()
                    .map(this::executableFile)
                    .filter(java.util.Objects::nonNull)
                    .map(executable -> candidate(executable, environment.get("PATH"), "environment override"))
                    .toList();
            if (configured.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "Configured {0} Runtime executable is unavailable: {1}",
                        provider.name(), override);
                return List.of();
            }
            return configured;
        }

        LinkedHashMap<Path, ExecutableCandidate> candidates = new LinkedHashMap<>();
        addFromPath(candidates, command, environment.get("PATH"), "desktop process PATH");
        if (candidates.isEmpty()) {
            addLoginShellCandidate(candidates, command);
        }
        for (Path directory : commonExecutableDirectories()) {
            addFromDirectory(candidates, command, directory,
                    pathWithDirectory(directory, environment.get("PATH")), "common installation directory");
        }
        for (Path executable : providerSpecificCandidates(provider)) {
            Path resolved = executableFile(executable);
            if (resolved != null) {
                add(candidates, candidate(resolved,
                        pathWithDirectory(resolved.getParent(), environment.get("PATH")), "provider fallback"));
            }
        }
        return List.copyOf(candidates.values());
    }

    private void addFromPath(Map<Path, ExecutableCandidate> candidates, String command,
                             String pathValue, String source) {
        if (pathValue == null || pathValue.isBlank()) {
            return;
        }
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(pathSeparator()))) {
            if (!entry.isBlank()) {
                addFromDirectory(candidates, command, Path.of(unquote(entry.trim())), pathValue, source);
            }
        }
    }

    private void addFromDirectory(Map<Path, ExecutableCandidate> candidates, String command,
                                  Path directory, String executionPath, String source) {
        for (Path path : commandCandidates(directory.resolve(command))) {
            Path executable = executableFile(path);
            if (executable != null) {
                add(candidates, candidate(executable, executionPath, source));
            }
        }
    }

    private List<Path> commandCandidates(Path candidate) {
        String fileName = candidate.getFileName().toString();
        if (!isWindows() || fileName.indexOf('.') >= 0) {
            return List.of(candidate);
        }
        // npm installs a Unix shell script without an extension next to the usable
        // Windows shims. Prefer native and Windows launchers before that script.
        return List.of(
                candidate.resolveSibling(fileName + ".exe"),
                candidate.resolveSibling(fileName + ".cmd"),
                candidate.resolveSibling(fileName + ".bat"),
                candidate.resolveSibling(fileName + ".ps1"),
                candidate);
    }

    private void addLoginShellCandidate(Map<Path, ExecutableCandidate> candidates, String command) {
        if (isWindows()) {
            return;
        }
        for (Path shell : loginShells()) {
            ShellResolution resolution = findFromLoginShell(shell, command);
            if (resolution != null) {
                add(candidates, candidate(resolution.executable(), resolution.path(), "login shell " + shell));
                return;
            }
        }
    }

    private List<Path> loginShells() {
        LinkedHashSet<Path> shells = new LinkedHashSet<>();
        String configured = trim(environment.get("SHELL"));
        if (configured != null) {
            shells.add(Path.of(configured));
        }
        shells.add(Path.of("/bin/zsh"));
        shells.add(Path.of("/bin/bash"));
        shells.add(Path.of("/bin/sh"));
        return shells.stream()
                .filter(Files::isExecutable)
                .filter(shell -> Set.of("bash", "zsh", "sh", "dash", "ksh")
                        .contains(shell.getFileName().toString()))
                .toList();
    }

    private ShellResolution findFromLoginShell(Path shell, String command) {
        Process process = null;
        try {
            String script = "unalias " + command + " >/dev/null 2>&1 || true; "
                    + "unset -f " + command + " >/dev/null 2>&1 || true; "
                    + "_chat2db_executable=$(command -v " + command + " 2>/dev/null) || exit 127; "
                    + "printf '\\n" + EXECUTABLE_MARKER + "%s\\n' \"$_chat2db_executable\"; "
                    + "printf '" + PATH_MARKER + "%s\\n' \"$PATH\"";
            process = new ProcessBuilder(shell.toString(), "-ilc", script)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return null;
            }
            String executableValue = markerValue(output, EXECUTABLE_MARKER);
            String pathValue = markerValue(output, PATH_MARKER);
            if (executableValue == null || !executableValue.startsWith("/")) {
                return null;
            }
            Path executable = executableFile(Path.of(executableValue));
            return executable == null ? null : new ShellResolution(executable, pathValue);
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String markerValue(String output, String marker) {
        return output.lines()
                .filter(line -> line.startsWith(marker))
                .map(line -> trim(line.substring(marker.length())))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    private List<Path> commonExecutableDirectories() {
        LinkedHashSet<Path> directories = new LinkedHashSet<>();
        addEnvironmentDirectory(directories, "NVM_SYMLINK");
        addEnvironmentDirectory(directories, "PNPM_HOME");
        String bunInstall = trim(environment.get("BUN_INSTALL"));
        if (bunInstall != null) {
            directories.add(Path.of(bunInstall).resolve("bin"));
        }
        if (isWindows()) {
            String appData = trim(environment.get("APPDATA"));
            if (appData != null) {
                directories.add(Path.of(appData).resolve("npm"));
            }
        } else {
            directories.add(userHome.resolve(".local/bin"));
            directories.add(userHome.resolve(".volta/bin"));
            directories.add(userHome.resolve(".bun/bin"));
            directories.add(Path.of("/opt/homebrew/bin"));
            directories.add(Path.of("/usr/local/bin"));
            addVersionManagerBins(directories, userHome.resolve(".nvm/versions/node"), "bin");
            addVersionManagerBins(directories, userHome.resolve(".local/share/fnm/node-versions"), "installation/bin");
            addVersionManagerBins(directories,
                    userHome.resolve("Library/Application Support/fnm/node-versions"), "installation/bin");
        }
        return directories.stream().filter(Files::isDirectory).toList();
    }

    private void addEnvironmentDirectory(Set<Path> directories, String name) {
        String value = trim(environment.get(name));
        if (value != null) {
            directories.add(Path.of(value));
        }
    }

    private void addVersionManagerBins(Set<Path> directories, Path versionsRoot, String relativeBin) {
        if (!Files.isDirectory(versionsRoot)) {
            return;
        }
        try (var versions = Files.list(versionsRoot)) {
            versions.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .map(version -> version.resolve(relativeBin))
                    .filter(Files::isDirectory)
                    .forEach(directories::add);
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.DEBUG, "Could not inspect Runtime version manager directory "
                    + versionsRoot + ": " + exception.getMessage());
        }
    }

    private List<Path> providerSpecificCandidates(AgentRuntimeProviderEnum provider) {
        if (provider != AgentRuntimeProviderEnum.CODEX || !isMac()) {
            return List.of();
        }
        return List.of(
                Path.of("/Applications/ChatGPT.app/Contents/Resources/codex"),
                Path.of("/Applications/Codex.app/Contents/Resources/codex"),
                userHome.resolve("Applications/ChatGPT.app/Contents/Resources/codex"),
                userHome.resolve("Applications/Codex.app/Contents/Resources/codex"));
    }

    private ExecutableCandidate candidate(Path executable, String pathValue, String source) {
        String executionPath = pathWithDirectory(executable.getParent(), pathValue);
        Map<String, String> candidateEnvironment = executionPath == null
                ? Map.of() : Map.of("PATH", executionPath);
        return new ExecutableCandidate(executable, candidateEnvironment, source);
    }

    private void add(Map<Path, ExecutableCandidate> candidates, ExecutableCandidate candidate) {
        candidates.putIfAbsent(candidate.executable(), candidate);
    }

    private String pathWithDirectory(Path directory, String pathValue) {
        if (directory == null) {
            return trim(pathValue);
        }
        String directoryValue = directory.toAbsolutePath().normalize().toString();
        if (pathValue == null || pathValue.isBlank()) {
            return directoryValue;
        }
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(pathSeparator()))) {
            if (directoryValue.equalsIgnoreCase(entry.trim())) {
                return pathValue;
            }
        }
        return directoryValue + pathSeparator() + pathValue;
    }

    private Path executableFile(Path candidate) {
        try {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                return null;
            }
            if (!isWindowsScript(normalized) && !Files.isExecutable(normalized)) {
                return null;
            }
            // Keep npm/nvm shim paths instead of resolving their symbolic links to a
            // JavaScript file in node_modules. The shim directory commonly owns node.
            return normalized;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<String> windowsCommand(Path executable, List<String> arguments) {
        ArrayList<String> command = new ArrayList<>();
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isWindows() && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
            command.addAll(List.of("cmd.exe", "/d", "/s", "/c", executable.toString()));
        } else if (isWindows() && name.endsWith(".ps1")) {
            command.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", executable.toString()));
        } else {
            command.add(executable.toString());
        }
        command.addAll(arguments);
        return command;
    }

    private boolean isWindowsScript(Path path) {
        if (!isWindows()) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1");
    }

    private boolean isWindows() {
        return operatingSystem.toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isMac() {
        return operatingSystem.toLowerCase(Locale.ROOT).contains("mac");
    }

    private String pathSeparator() {
        return isWindows() ? ";" : ":";
    }

    private String pathEnvironment(AgentRuntimeProviderEnum provider) {
        return switch (provider) {
            case CLAUDE_CODE -> CLAUDE_CODE_PATH_ENV;
            case CODEX -> CODEX_PATH_ENV;
            case OPENCODE -> OPENCODE_PATH_ENV;
            case PI -> PI_PATH_ENV;
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

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record ExecutableCandidate(Path executable, Map<String, String> environment, String source) {
    }

    private record ShellResolution(Path executable, String path) {
    }
}
