package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateAuditLogTest {

    @TempDir
    Path tempDirectory;

    @Test
    void persistsFailureReasonAndExposesThePreviousOperationLog() throws Exception {
        AtomicReference<Path> openedPath = new AtomicReference<>();
        UpdateAuditLog auditLog = new UpdateAuditLog(tempDirectory, path -> {
            openedPath.set(path);
            return true;
        });

        auditLog.begin();
        auditLog.versions("5.3.4", "5.3.5");
        auditLog.info("DOWNLOAD", "request started");
        auditLog.failure("DOWNLOAD_VERIFY", new IOException("checksum mismatch"));

        String result = Files.readString(tempDirectory.resolve("latest-result.properties"));
        assertTrue(result.contains("status=FAILED"));
        assertTrue(result.contains("stage=DOWNLOAD_VERIFY"));
        assertTrue(result.contains("reason=checksum mismatch"));

        Path operationLog;
        try (var directories = Files.list(tempDirectory)) {
            operationLog = directories
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow()
                    .resolve("update.log");
        }
        String log = Files.readString(operationLog);
        assertTrue(log.contains("stage=CHECK"));
        assertTrue(log.contains("stage=DOWNLOAD"));
        assertTrue(log.contains("stage=DOWNLOAD_VERIFY"));
        assertTrue(log.contains("checksum mismatch"));
        assertTrue(log.contains("java.io.IOException"));
        assertTrue(auditLog.openRecoveryLog());
        assertEquals(operationLog, openedPath.get());

        UpdateAuditLog restarted = new UpdateAuditLog(tempDirectory, path -> {
            openedPath.set(path);
            return true;
        });
        DesktopUpdateRecoveryStatus recovery = restarted.recoveryStatus();
        assertTrue(recovery.failed());
        assertEquals("5.3.4", recovery.fromVersion());
        assertEquals("5.3.5", recovery.toVersion());
        assertTrue(restarted.openRecoveryLog());
        assertEquals(operationLog, openedPath.get());
    }

    @Test
    void preparesNativeHandoffPathsAndPendingResult() throws Exception {
        UpdateAuditLog auditLog = new UpdateAuditLog(tempDirectory, path -> false);
        auditLog.begin();
        auditLog.versions("5.3.4", "5.3.5");

        UpdateAuditLog.NativeContext context = auditLog.prepareNativeHandoff();

        assertNotNull(context);
        assertTrue(Files.isRegularFile(context.logFile()));
        assertEquals(context.logFile().getParent().resolve("native-installer.log"), context.nativeInstallerLog());
        String result = Files.readString(context.resultFile());
        assertTrue(result.contains("status=PENDING"));
        assertTrue(result.contains("stage=HANDOFF"));
        assertTrue(result.contains("fromVersion=5.3.4"));
        assertTrue(result.contains("toVersion=5.3.5"));
        assertFalse(auditLog.recoveryStatus().failed());
    }

    @Test
    void readsWindowsPowerShellUtf8BomResultFiles() throws Exception {
        Path operationDirectory = tempDirectory.resolve("windows-operation");
        Files.createDirectories(operationDirectory);
        Path logFile = operationDirectory.resolve("update.log");
        Files.writeString(logFile, "windows failure");
        Files.writeString(
                tempDirectory.resolve("latest-result.properties"),
                "\ufeffstatus=FAILED\nstage=INSTALL_MSI\nexitCode=1603\nreason=MSI failed\n"
                        + "operationId=windows-operation\nfromVersion=5.3.4\ntoVersion=5.3.5\n"
                        + "logPath=" + logFile + "\n"
        );
        AtomicReference<Path> openedPath = new AtomicReference<>();

        UpdateAuditLog restarted = new UpdateAuditLog(tempDirectory, path -> {
            openedPath.set(path);
            return true;
        });

        DesktopUpdateRecoveryStatus recovery = restarted.recoveryStatus();
        assertTrue(recovery.failed());
        assertEquals("5.3.4", recovery.fromVersion());
        assertEquals("5.3.5", recovery.toVersion());
        assertTrue(restarted.openRecoveryLog());
        assertEquals(logFile, openedPath.get());
    }

    @Test
    void treatsInterruptedNativeHandoffAsARecoverableFailure() throws Exception {
        Path operationDirectory = tempDirectory.resolve("interrupted-operation");
        Files.createDirectories(operationDirectory);
        Path logFile = operationDirectory.resolve("update.log");
        Files.writeString(logFile, "stage=WAIT_PARENT");
        Files.writeString(
                tempDirectory.resolve("latest-result.properties"),
                "status=PENDING\nstage=HANDOFF\nexitCode=\nreason=\n"
                        + "operationId=interrupted-operation\nfromVersion=5.3.4\ntoVersion=5.3.5\n"
                        + "logPath=" + logFile + "\n"
        );

        UpdateAuditLog restarted = new UpdateAuditLog(tempDirectory, path -> true);

        DesktopUpdateRecoveryStatus recovery = restarted.recoveryStatus();
        assertTrue(recovery.failed());
        assertEquals("5.3.4", recovery.fromVersion());
        assertEquals("5.3.5", recovery.toVersion());
    }

    @Test
    void repeatedChecksContinueTheActiveOperationAndTerminalChecksStartANewOne() throws Exception {
        UpdateAuditLog auditLog = new UpdateAuditLog(tempDirectory, path -> true);
        auditLog.begin();
        auditLog.versions("5.3.4", "5.3.5");
        UpdateAuditLog.NativeContext first = auditLog.prepareNativeHandoff();

        auditLog.begin();
        UpdateAuditLog.NativeContext continued = auditLog.prepareNativeHandoff();

        assertEquals(first.operationId(), continued.operationId());
        assertEquals(first.logFile(), continued.logFile());
        assertTrue(Files.readString(first.logFile()).contains("continuing active operation"));

        auditLog.complete("CHECK", "terminal");
        auditLog.begin();
        UpdateAuditLog.NativeContext next = auditLog.prepareNativeHandoff();
        assertFalse(first.operationId().equals(next.operationId()));
    }
}
