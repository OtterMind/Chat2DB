package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class ProviderProcessRegistry {

    static final String FILE_NAME = ".chat2db-runtime-processes.json";
    private static final int MAX_REGISTRY_BYTES = 1024 * 1024;

    private final Path root;
    private final Path file;
    private final ObjectMapper mapper;

    public ProviderProcessRegistry(Path workspaceRoot) {
        if (workspaceRoot == null || !workspaceRoot.isAbsolute()) {
            throw new IllegalArgumentException("Process registry requires an absolute Runtime workspace root");
        }
        this.root = workspaceRoot.normalize();
        this.file = root.resolve(FILE_NAME).normalize();
        this.mapper = new ObjectMapper();
    }

    public synchronized void register(String daemonId, AgentRuntimeProviderEnum provider,
                                      String runId, int leaseAttempt, String runtimeExecutionId,
                                      long processId, Instant processStartInstant,
                                      String executable, Path workspace) {
        if (processId <= 0 || processStartInstant == null) return;
        if (StringUtils.isAnyBlank(daemonId, runId, runtimeExecutionId, executable)
                || provider == null || leaseAttempt <= 0 || workspace == null) {
            throw new IllegalArgumentException("Provider process identity is incomplete");
        }
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        if (normalizedWorkspace.equals(root) || !normalizedWorkspace.startsWith(root)) {
            throw new SecurityException("Provider process workspace is outside the Runtime root");
        }
        List<Entry> entries = load();
        entries.removeIf(entry -> daemonId.equals(entry.getDaemonId())
                && runId.equals(entry.getRunId()) && leaseAttempt == entry.getLeaseAttempt());
        Entry entry = new Entry();
        entry.setDaemonId(daemonId);
        entry.setProvider(provider);
        entry.setRunId(runId);
        entry.setLeaseAttempt(leaseAttempt);
        entry.setRuntimeExecutionId(runtimeExecutionId);
        entry.setProcessId(processId);
        entry.setProcessStartEpochMillis(processStartInstant.toEpochMilli());
        entry.setExecutable(executable);
        entry.setWorkspace(normalizedWorkspace.toString());
        entry.setRegisteredAtEpochMillis(System.currentTimeMillis());
        entries.add(entry);
        persist(entries);
    }

    public synchronized void unregister(String daemonId, String runId, int leaseAttempt) {
        List<Entry> entries = load();
        if (entries.removeIf(entry -> daemonId.equals(entry.getDaemonId())
                && runId.equals(entry.getRunId()) && leaseAttempt == entry.getLeaseAttempt())) {
            persist(entries);
        }
    }

    public synchronized RecoveryReport reapOrphans(String daemonId, AgentRuntimeProviderEnum provider) {
        List<Entry> entries = load();
        List<Entry> recovered = new ArrayList<>();
        List<Entry> quarantined = new ArrayList<>();
        for (Entry entry : List.copyOf(entries)) {
            if (!daemonId.equals(entry.getDaemonId()) || provider != entry.getProvider()) continue;
            ProcessHandle handle = ProcessHandle.of(entry.getProcessId()).orElse(null);
            if (handle == null || !handle.isAlive()) {
                entries.remove(entry);
                recovered.add(entry);
                continue;
            }
            if (!matchesIdentity(handle, entry)) {
                quarantined.add(entry);
                continue;
            }
            terminateTree(handle);
            if (handle.isAlive()) {
                quarantined.add(entry);
                continue;
            }
            entries.remove(entry);
            recovered.add(entry);
        }
        persist(entries);
        return new RecoveryReport(List.copyOf(recovered), List.copyOf(quarantined));
    }

    private boolean matchesIdentity(ProcessHandle handle, Entry entry) {
        long actualStart = handle.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        if (actualStart <= 0 || actualStart != entry.getProcessStartEpochMillis()) return false;
        String actualCommand = handle.info().command().orElse(null);
        if (StringUtils.isBlank(actualCommand)) return false;
        try {
            String expectedName = Path.of(entry.getExecutable()).getFileName().toString();
            String actualName = Path.of(actualCommand).getFileName().toString();
            return expectedName.equals(actualName);
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    private void terminateTree(ProcessHandle handle) {
        List<ProcessHandle> descendants = handle.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        handle.destroy();
        awaitExit(handle);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (handle.isAlive()) {
            handle.destroyForcibly();
            awaitExit(handle);
        }
    }

    private void awaitExit(ProcessHandle handle) {
        if (!handle.isAlive()) return;
        try {
            handle.onExit().get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A verified owned process that ignores graceful termination is force-killed by the caller.
        }
    }

    private List<Entry> load() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        try {
            var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.size() > MAX_REGISTRY_BYTES) {
                throw new SecurityException("Runtime process registry is not a safe regular file");
            }
            byte[] bytes;
            try (var input = Files.newInputStream(file, java.nio.file.StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_REGISTRY_BYTES + 1);
            }
            if (bytes.length > MAX_REGISTRY_BYTES) {
                throw new SecurityException("Runtime process registry exceeds the size limit");
            }
            List<Entry> entries = mapper.readValue(bytes, new TypeReference<List<Entry>>() { });
            return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read Runtime process registry", exception);
        }
    }

    private void persist(List<Entry> entries) {
        requireSafeRoot();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".chat2db-runtime-processes-", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows and some mounted filesystems do not expose POSIX permissions.
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), entries);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist Runtime process registry", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private void requireSafeRoot() {
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException("Runtime workspace root is not a safe directory");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize Runtime process registry", exception);
        }
    }

    public record RecoveryReport(List<Entry> recovered, List<Entry> quarantined) {
    }

    public static class Entry {
        private String daemonId;
        private AgentRuntimeProviderEnum provider;
        private String runId;
        private int leaseAttempt;
        private String runtimeExecutionId;
        private long processId;
        private long processStartEpochMillis;
        private String executable;
        private String workspace;
        private long registeredAtEpochMillis;

        public String getDaemonId() { return daemonId; }
        public void setDaemonId(String daemonId) { this.daemonId = daemonId; }
        public AgentRuntimeProviderEnum getProvider() { return provider; }
        public void setProvider(AgentRuntimeProviderEnum provider) { this.provider = provider; }
        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public int getLeaseAttempt() { return leaseAttempt; }
        public void setLeaseAttempt(int leaseAttempt) { this.leaseAttempt = leaseAttempt; }
        public String getRuntimeExecutionId() { return runtimeExecutionId; }
        public void setRuntimeExecutionId(String runtimeExecutionId) { this.runtimeExecutionId = runtimeExecutionId; }
        public long getProcessId() { return processId; }
        public void setProcessId(long processId) { this.processId = processId; }
        public long getProcessStartEpochMillis() { return processStartEpochMillis; }
        public void setProcessStartEpochMillis(long value) { this.processStartEpochMillis = value; }
        public String getExecutable() { return executable; }
        public void setExecutable(String executable) { this.executable = executable; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        public long getRegisteredAtEpochMillis() { return registeredAtEpochMillis; }
        public void setRegisteredAtEpochMillis(long value) { this.registeredAtEpochMillis = value; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry && Objects.equals(daemonId, entry.daemonId)
                    && Objects.equals(runId, entry.runId) && leaseAttempt == entry.leaseAttempt;
        }

        @Override
        public int hashCode() {
            return Objects.hash(daemonId, runId, leaseAttempt);
        }
    }
}
