package ai.chat2db.community.jcef.update;

import org.cef.OS;

import java.awt.Desktop;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class UpdateAuditLog {

    @FunctionalInterface
    interface LogOpener {
        boolean open(Path path) throws IOException;
    }

    record NativeContext(
            String operationId,
            Path logFile,
            Path resultFile,
            String fromVersion,
            String toVersion
    ) {
    }

    private record RecoveryRecord(boolean failed, String fromVersion, String toVersion, Path logFile) {
        private static RecoveryRecord none() {
            return new RecoveryRecord(false, "", "", null);
        }
    }

    private static final DateTimeFormatter OPERATION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ISO_INSTANT;
    private static final int RETAINED_OPERATION_COUNT = 20;

    private final Path rootDirectory;
    private final LogOpener logOpener;
    private final boolean enabled;
    private final RecoveryRecord startupRecovery;

    private String operationId;
    private Path operationDirectory;
    private Path logFile;
    private Path resultFile;
    private String fromVersion = "";
    private String toVersion = "";
    private boolean currentFailed;
    private boolean currentTerminal = true;
    private boolean auditHealthy = true;

    static UpdateAuditLog createDefault() {
        Path applicationLog = resolveApplicationLog();
        return new UpdateAuditLog(applicationLog.getParent().resolve("update"), UpdateAuditLog::openWithSystem, true);
    }

    static UpdateAuditLog disabled() {
        return new UpdateAuditLog(null, path -> false, false);
    }

    UpdateAuditLog(Path rootDirectory, LogOpener logOpener) {
        this(rootDirectory, logOpener, true);
    }

    private UpdateAuditLog(Path rootDirectory, LogOpener logOpener, boolean enabled) {
        this.rootDirectory = rootDirectory == null ? null : rootDirectory.toAbsolutePath().normalize();
        this.logOpener = logOpener;
        this.enabled = enabled;
        this.startupRecovery = enabled ? loadRecovery(this.rootDirectory.resolve("latest-result.properties"))
                : RecoveryRecord.none();
    }

    synchronized void begin() {
        if (!enabled) {
            return;
        }
        if (logFile != null && !currentTerminal) {
            append("INFO", "CHECK", "continuing active operation id=" + operationId);
            return;
        }
        try {
            Files.createDirectories(rootDirectory);
            operationId = OPERATION_TIME.format(Instant.now()) + "-" + UUID.randomUUID();
            operationDirectory = rootDirectory.resolve(operationId);
            Files.createDirectories(operationDirectory);
            logFile = operationDirectory.resolve("update.log");
            resultFile = rootDirectory.resolve("latest-result.properties");
            fromVersion = "";
            toVersion = "";
            currentFailed = false;
            currentTerminal = false;
            auditHealthy = true;
            writeResult("CHECKING", "CHECK", "", "");
            append("INFO", "CHECK", "operation started id=" + operationId);
            pruneOldOperations();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize the update audit log", exception);
        }
    }

    synchronized void versions(String fromVersion, String toVersion) {
        this.fromVersion = safe(fromVersion);
        this.toVersion = safe(toVersion);
        info("RELEASE", "version transition " + this.fromVersion + " -> " + this.toVersion);
    }

    synchronized void info(String stage, String message) {
        append("INFO", stage, message);
    }

    synchronized void warn(String stage, String message) {
        append("WARN", stage, message);
    }

    synchronized void failure(String stage, Throwable exception) {
        if (!enabled || logFile == null) {
            return;
        }
        String reason = exception == null ? "unknown failure" : safe(exception.getMessage());
        append("ERROR", stage, "failure reason=" + reason);
        if (exception != null) {
            StringWriter stack = new StringWriter();
            exception.printStackTrace(new PrintWriter(stack));
            appendRaw(stack.toString());
        }
        currentFailed = true;
        currentTerminal = true;
        try {
            writeResult("FAILED", stage, "", reason);
        } catch (IOException resultException) {
            append("ERROR", "AUDIT", "could not persist failure result: " + resultException.getMessage());
        }
    }

    synchronized void complete(String stage, String message) {
        append("INFO", stage, message);
        if (!enabled || resultFile == null) {
            return;
        }
        currentFailed = false;
        currentTerminal = true;
        try {
            writeResult("SUCCESS", stage, "0", "");
        } catch (IOException exception) {
            append("ERROR", "AUDIT", "could not persist success result: " + exception.getMessage());
        }
    }

    synchronized NativeContext prepareNativeHandoff() throws IOException {
        if (!enabled || logFile == null || resultFile == null) {
            return null;
        }
        if (!auditHealthy) {
            throw new IOException("Update audit log is not writable");
        }
        appendRequired("INFO", "HANDOFF", "native installer audit prepared log=" + logFile);
        writeResult("PENDING", "HANDOFF", "", "");
        return new NativeContext(
                operationId,
                logFile,
                resultFile,
                fromVersion,
                toVersion
        );
    }

    DesktopUpdateRecoveryStatus recoveryStatus() {
        if (!startupRecovery.failed()) {
            return DesktopUpdateRecoveryStatus.none();
        }
        return new DesktopUpdateRecoveryStatus(
                true,
                startupRecovery.fromVersion(),
                startupRecovery.toVersion()
        );
    }

    boolean openRecoveryLog() {
        Path recoveryLog = currentFailureLog();
        if (recoveryLog == null && startupRecovery.failed()) {
            recoveryLog = startupRecovery.logFile();
        }
        if (recoveryLog == null) {
            recoveryLog = currentLogFile();
        }
        if (recoveryLog == null || !Files.isRegularFile(recoveryLog)) {
            return false;
        }
        try {
            return logOpener.open(recoveryLog);
        } catch (IOException exception) {
            return false;
        }
    }

    private synchronized Path currentLogFile() {
        return logFile;
    }

    private synchronized Path currentFailureLog() {
        return currentFailed ? logFile : null;
    }

    private void append(String level, String stage, String message) {
        if (!enabled || logFile == null) {
            return;
        }
        appendRaw(formatLine(level, stage, message));
    }

    private void appendRequired(String level, String stage, String message) throws IOException {
        if (!enabled || logFile == null) {
            return;
        }
        Files.writeString(
                logFile,
                formatLine(level, stage, message),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        forceFile(logFile);
    }

    private static String formatLine(String level, String stage, String message) {
        return LOG_TIME.format(Instant.now())
                + " level=" + safe(level)
                + " stage=" + safe(stage)
                + " " + safe(message)
                + System.lineSeparator();
    }

    private void appendRaw(String text) {
        if (!enabled || logFile == null) {
            return;
        }
        try {
            Files.writeString(
                    logFile,
                    text,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            auditHealthy = false;
        }
    }

    private void writeResult(String status, String stage, String exitCode, String reason) throws IOException {
        Path temporary = resultFile.resolveSibling(resultFile.getFileName() + ".tmp-" + UUID.randomUUID());
        List<String> lines = List.of(
                "status=" + safe(status),
                "stage=" + safe(stage),
                "exitCode=" + safe(exitCode),
                "reason=" + safe(reason),
                "operationId=" + safe(operationId),
                "fromVersion=" + safe(fromVersion),
                "toVersion=" + safe(toVersion),
                "logPath=" + safe(logFile == null ? "" : logFile.toString())
        );
        Files.write(temporary, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        forceFile(temporary);
        try {
            Files.move(temporary, resultFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, resultFile, StandardCopyOption.REPLACE_EXISTING);
        }
        forceFile(resultFile);
    }

    private void pruneOldOperations() {
        try (var paths = Files.list(rootDirectory)) {
            List<Path> operationDirectories = paths
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(UpdateAuditLog::lastModified).reversed())
                    .toList();
            for (int index = RETAINED_OPERATION_COUNT; index < operationDirectories.size(); index++) {
                deleteDirectory(operationDirectories.get(index));
            }
        } catch (IOException exception) {
            append("WARN", "AUDIT", "could not prune old update logs: " + exception.getMessage());
        }
    }

    private static RecoveryRecord loadRecovery(Path resultFile) {
        if (!Files.isRegularFile(resultFile)) {
            return RecoveryRecord.none();
        }
        try {
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    String key = line.substring(0, separator);
                    if (!key.isEmpty() && key.charAt(0) == '\ufeff') {
                        key = key.substring(1);
                    }
                    values.put(key, line.substring(separator + 1));
                }
            }
            String status = values.get("status");
            if (!"FAILED".equals(status) && !"PENDING".equals(status)) {
                return RecoveryRecord.none();
            }
            String logPath = values.getOrDefault("logPath", "");
            Path logFile = logPath.isBlank() ? null : Path.of(logPath).toAbsolutePath().normalize();
            return new RecoveryRecord(
                    true,
                    values.getOrDefault("fromVersion", ""),
                    values.getOrDefault("toVersion", ""),
                    logFile
            );
        } catch (Exception ignored) {
            return RecoveryRecord.none();
        }
    }

    private static Path resolveApplicationLog() {
        String configured = System.getProperty("log.path");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(
                System.getProperty("user.home"),
                ".chat2db",
                "chat2db-community",
                "logs",
                "application.log"
        ).toAbsolutePath().normalize();
    }

    private static boolean openWithSystem(Path path) throws IOException {
        if (OS.isMacintosh()) {
            new ProcessBuilder("open", "-a", "Console", path.toString()).start();
            return true;
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(path.toFile());
            return true;
        }
        return false;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
