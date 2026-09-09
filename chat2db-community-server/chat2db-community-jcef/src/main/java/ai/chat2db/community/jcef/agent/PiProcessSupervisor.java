package ai.chat2db.community.jcef.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentModelAccess;

public class PiProcessSupervisor implements AutoCloseable {

    private final PiRuntimeLayout layout;
    private final Path sessionDataRoot;
    private final int maximumProcesses;
    private final ProcessStarter processStarter;
    private final PiRuntimePreflight preflight;
    private final Map<String, PiProcessHandle> processes = new LinkedHashMap<>();
    private boolean closed;

    public PiProcessSupervisor(
            PiRuntimeLayout layout,
            Path sessionDataRoot,
            int maximumProcesses,
            PiRuntimePreflight preflight) {
        this(layout, sessionDataRoot, maximumProcesses, preflight, ProcessBuilder::start);
    }

    PiProcessSupervisor(
            PiRuntimeLayout layout,
            Path sessionDataRoot,
            int maximumProcesses,
            ProcessStarter processStarter) {
        this(layout, sessionDataRoot, maximumProcesses, () -> { }, processStarter);
    }

    PiProcessSupervisor(
            PiRuntimeLayout layout,
            Path sessionDataRoot,
            int maximumProcesses,
            PiRuntimePreflight preflight,
            ProcessStarter processStarter) {
        if (maximumProcesses < 1) {
            throw new IllegalArgumentException("maximumProcesses must be greater than zero");
        }
        this.layout = layout;
        this.sessionDataRoot = sessionDataRoot.toAbsolutePath().normalize();
        this.maximumProcesses = maximumProcesses;
        this.preflight = preflight;
        this.processStarter = processStarter;
    }

    public synchronized PiProcessHandle start(
            String sessionId,
            String externalSessionId,
            List<Path> extensions) throws IOException {
        return start(sessionId, externalSessionId, extensions, null);
    }

    public synchronized PiProcessHandle start(
            String sessionId,
            String externalSessionId,
            List<Path> extensions,
            AgentModelAccess modelAccess) throws IOException {
        requireText(sessionId, "sessionId");
        requireText(externalSessionId, "externalSessionId");
        if (closed) {
            throw new IllegalStateException("Pi process supervisor is closed");
        }
        if (processes.containsKey(sessionId)) {
            throw new IllegalStateException("Pi process already exists for session: " + sessionId);
        }
        if (processes.size() >= maximumProcesses) {
            throw new IllegalStateException("Pi process limit has been reached");
        }
        preflight.verify();
        String os = System.getProperty("os.name", "unknown");
        String architecture = System.getProperty("os.arch", "unknown");
        Path executable = layout.executable(os, architecture);
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pi runtime executable is unavailable");
        }
        Path sessionDirectory = sessionDataRoot.resolve("sessions").resolve(sessionId).normalize();
        Path configDirectory = configurationDirectory(sessionId);
        if (!sessionDirectory.startsWith(sessionDataRoot.resolve("sessions"))
                || !configDirectory.startsWith(sessionDataRoot.resolve("config"))) {
            throw new IOException("Pi session path is unsafe");
        }
        Files.createDirectories(sessionDirectory);
        Files.createDirectories(configDirectory);
        ProcessBuilder builder = new ProcessBuilder(command(
                executable, externalSessionId, sessionDirectory, extensions, modelAccess));
        builder.directory(sessionDirectory.toFile());
        builder.environment().clear();
        builder.environment().put("PI_CODING_AGENT_DIR", configDirectory.toString());
        if (modelAccess != null) {
            builder.environment().put("CHAT2DB_MODEL_TICKET", modelAccess.ticket());
        }
        Process process = processStarter.start(builder);
        PiProcessHandle handle = new PiProcessHandle(sessionId, process);
        processes.put(sessionId, handle);
        process.onExit().thenRun(() -> remove(sessionId, handle));
        return handle;
    }

    private List<String> command(
            Path executable,
            String externalSessionId,
            Path sessionDirectory,
            List<Path> extensions,
            AgentModelAccess modelAccess) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                executable.toString(), "--mode", "rpc",
                "--session-id", externalSessionId,
                "--session-dir", sessionDirectory.toString(),
                "--no-builtin-tools", "--no-extensions"));
        if (modelAccess != null) {
            command.add("--provider");
            command.add(modelAccess.provider());
            command.add("--model");
            command.add(modelAccess.modelId());
        }
        for (Path extension : extensions == null ? List.<Path>of() : extensions) {
            Path file = extension.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Pi extension is unavailable or unsafe");
            }
            command.add("--extension");
            command.add(file.toString());
        }
        command.addAll(List.of(
                "--no-skills", "--no-prompt-templates", "--no-themes",
                "--no-context-files", "--no-approve", "--offline"));
        return List.copyOf(command);
    }

    public synchronized Path prepareConfigurationDirectory(String sessionId) throws IOException {
        if (closed) {
            throw new IllegalStateException("Pi process supervisor is closed");
        }
        Path directory = configurationDirectory(sessionId);
        Files.createDirectories(directory);
        return directory;
    }

    private Path configurationDirectory(String sessionId) throws IOException {
        requireText(sessionId, "sessionId");
        Path directory = sessionDataRoot.resolve("config").resolve(sessionId).normalize();
        if (!directory.startsWith(sessionDataRoot.resolve("config"))) {
            throw new IOException("Pi configuration path is unsafe");
        }
        return directory;
    }

    private synchronized void remove(String sessionId, PiProcessHandle expected) {
        processes.remove(sessionId, expected);
    }

    public synchronized int size() {
        return processes.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        List<PiProcessHandle> activeProcesses = List.copyOf(processes.values());
        processes.clear();
        activeProcesses.forEach(PiProcessHandle::close);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }
}
