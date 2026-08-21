package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cef.OS;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Creates and launches the Windows elevated-helper plan; the helper remains the security boundary. */
final class ElevatedWindowsInstallationExecutor implements InstallationExecutor {

    static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 60_000L;
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 100L;

    @FunctionalInterface
    interface ProcessLauncher {
        void start(ProcessBuilder processBuilder) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Path appDirectory;
    private final Path workingDirectory;
    private final ObjectMapper objectMapper;
    private final Consumer<String> progressLog;
    private final Consumer<Exception> errorLog;
    private final ProcessLauncher processLauncher;
    private final Sleeper sleeper;
    private final long handshakeTimeoutMillis;
    private final long pollIntervalMillis;
    private final BooleanSupplier windowsPlatform;

    ElevatedWindowsInstallationExecutor(Path appDirectory, Path workingDirectory, ObjectMapper objectMapper,
                                         Consumer<String> progressLog, Consumer<Exception> errorLog) {
        this(appDirectory, workingDirectory, objectMapper, progressLog, errorLog,
                processBuilder -> processBuilder.start(), Thread::sleep,
                DEFAULT_HANDSHAKE_TIMEOUT_MILLIS, DEFAULT_POLL_INTERVAL_MILLIS, OS::isWindows);
    }

    ElevatedWindowsInstallationExecutor(Path appDirectory, Path workingDirectory, ObjectMapper objectMapper,
                                         Consumer<String> progressLog, Consumer<Exception> errorLog,
                                         ProcessLauncher processLauncher, Sleeper sleeper,
                                         long handshakeTimeoutMillis, long pollIntervalMillis) {
        this(appDirectory, workingDirectory, objectMapper, progressLog, errorLog, processLauncher, sleeper,
                handshakeTimeoutMillis, pollIntervalMillis, () -> true);
    }

    ElevatedWindowsInstallationExecutor(Path appDirectory, Path workingDirectory, ObjectMapper objectMapper,
                                         Consumer<String> progressLog, Consumer<Exception> errorLog,
                                         ProcessLauncher processLauncher, Sleeper sleeper,
                                         long handshakeTimeoutMillis, long pollIntervalMillis,
                                         BooleanSupplier windowsPlatform) {
        this.appDirectory = appDirectory.toAbsolutePath().normalize();
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.progressLog = progressLog;
        this.errorLog = errorLog;
        this.processLauncher = processLauncher;
        this.sleeper = sleeper;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        this.pollIntervalMillis = pollIntervalMillis;
        this.windowsPlatform = windowsPlatform;
    }

    @Override
    public boolean install(List<FileUpdateAction> actions, Map<String, Path> downloadedFiles,
                           VersionMetadata remoteMetadata) {
        if (!windowsPlatform.getAsBoolean()) {
            progressLog.accept("FATAL ERROR: The elevated updater is available only on Windows.");
            return false;
        }
        progressLog.accept("Preparing for update via auxiliary process...");
        Path planPath = null;
        Path statusPath = null;
        try {
            Files.createDirectories(workingDirectory);
            UpdatePlan plan = new UpdatePlan();
            plan.setTasks(actions);
            plan.setRemoteMetadata(remoteMetadata);
            plan.setDownloadedFiles(downloadedFiles.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            entry -> entry.getValue().toAbsolutePath().toString())));
            planPath = Files.createTempFile(workingDirectory, "chat2db-update-plan-", ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planPath.toFile(), plan);
            progressLog.accept("Update plan created at: " + planPath);

            Path updaterJar = appDirectory.resolve("updater.jar");
            if (!Files.exists(updaterJar)) {
                throw new FileNotFoundException("Updater executable not found at: " + updaterJar);
            }
            String operationId = UUID.randomUUID().toString();
            statusPath = workingDirectory.resolve("chat2db-update-status-" + operationId + ".txt");
            Files.deleteIfExists(statusPath);
            String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java.exe")
                    .toAbsolutePath().toString();
            ProcessBuilder processBuilder = processBuilder(javaExecutable, updaterJar, planPath, statusPath, operationId);
            progressLog.accept("Requesting elevation and waiting for updater acceptance...");
            processLauncher.start(processBuilder);
            HandshakeResult result = awaitHandshake(statusPath, operationId);
            if (result != HandshakeResult.ACCEPTED) {
                progressLog.accept("FATAL ERROR: Elevated updater did not accept the update plan (" + result + ").");
                deleteQuietly(planPath);
                return false;
            }
            progressLog.accept("Elevated updater accepted the verified plan. The application will now close.");
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errorLog.accept(exception);
            progressLog.accept("FATAL ERROR: Waiting for the elevated updater was interrupted.");
            deleteQuietly(planPath);
            return false;
        } catch (Exception exception) {
            errorLog.accept(exception);
            progressLog.accept("FATAL ERROR: Could not start the update process. " + exception.getMessage());
            deleteQuietly(planPath);
            return false;
        } finally {
            deleteQuietly(statusPath);
        }
    }

    ProcessBuilder processBuilder(String javaExecutable, Path updaterJar, Path planPath, Path statusPath,
                                  String operationId) {
        ProcessBuilder processBuilder = new ProcessBuilder("wscript.exe",
                appDirectory.resolve("run-as-admin.vbs").toString(), javaExecutable, updaterJar.toString(),
                planPath.toString(), appDirectory.toString(), "chat2db-community://restart",
                statusPath.toString(), operationId, workingDirectory.toString(),
                Long.toString(ProcessHandle.current().pid()));
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    private HandshakeResult awaitHandshake(Path statusPath, String operationId)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(handshakeTimeoutMillis);
        while (System.nanoTime() <= deadline) {
            if (Files.isRegularFile(statusPath)) {
                String status = Files.readString(statusPath, StandardCharsets.UTF_8).trim();
                String prefix = operationId + "|";
                if (status.isEmpty() || !status.contains("|")) {
                    sleeper.sleep(pollIntervalMillis);
                    continue;
                }
                if (!status.startsWith(prefix)) {
                    throw new IOException("Elevated updater returned a mismatched operation ID");
                }
                try {
                    return HandshakeResult.valueOf(status.substring(prefix.length()));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Elevated updater returned an invalid handshake status", exception);
                }
            }
            sleeper.sleep(pollIntervalMillis);
        }
        return HandshakeResult.TIMEOUT;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A failed helper may still own the plan/status file; startup recovery can clean it later.
        }
    }

    private enum HandshakeResult {
        ACCEPTED,
        FAILED,
        REJECTED,
        TIMEOUT
    }
}
