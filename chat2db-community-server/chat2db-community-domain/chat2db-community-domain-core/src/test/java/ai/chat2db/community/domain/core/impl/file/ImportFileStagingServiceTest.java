package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFileStagingServiceTest {

    @TempDir
    private Path tempDirectory;

    private final String originalUserHome = System.getProperty("user.home");

    private final String originalMaxSize = System.getProperty("chat2db.task.import.staging.max-bytes");

    private final String originalMaxFiles = System.getProperty("chat2db.task.import.staging.max-files");

    private final String originalMaxTotalSize = System.getProperty(
            "chat2db.task.import.staging.max-total-bytes");

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
        restoreProperty("chat2db.task.import.staging.max-bytes", originalMaxSize);
        restoreProperty("chat2db.task.import.staging.max-files", originalMaxFiles);
        restoreProperty("chat2db.task.import.staging.max-total-bytes", originalMaxTotalSize);
    }

    @Test
    void registersResolvesAndReleasesOnlyStagedImportFile() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("source.csv"), "id,name\n1,Ada\n").toFile();

        String fileId = stagingService.stage(source, "input.csv");
        File resolved = stagingService.resolve(fileId);

        assertTrue(resolved.isFile());
        assertEquals(fileId + ".csv", resolved.getName());
        assertTrue(resolved.toPath().normalize().toAbsolutePath().startsWith(tempDirectory));

        stagingService.release(fileId);

        assertFalse(Files.exists(resolved.toPath()));
    }

    @Test
    void rejectsInvalidFileId() {
        System.setProperty("user.home", tempDirectory.toString());

        assertThrows(BusinessException.class, () -> new ImportFileStagingService().resolve("../outside"));
    }

    @Test
    void acceptsEverySupportedWebImportFormat() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();

        for (String extension : new String[] {"csv", "xls", "xlsx", "json", "sql"}) {
            File source = Files.writeString(tempDirectory.resolve("source." + extension), "test").toFile();
            String fileId = stagingService.stage(source, "input." + extension);
            File resolved = stagingService.resolve(fileId);

            assertEquals(fileId + "." + extension, resolved.getName());
            stagingService.release(fileId);
        }
    }

    @Test
    void rejectsUnsupportedImportFormat() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        File source = Files.writeString(tempDirectory.resolve("source.zip"), "test").toFile();

        assertThrows(BusinessException.class, () -> new ImportFileStagingService().stage(source, "input.zip"));
    }

    @Test
    void appliesTheConfigurableImportSizeLimit() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        System.setProperty("chat2db.task.import.staging.max-bytes", "16");
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File accepted = Files.writeString(tempDirectory.resolve("accepted.csv"), "abcdefghijklmnop").toFile();
        File rejected = Files.writeString(tempDirectory.resolve("rejected.csv"), "abcdefghijklmnopq").toFile();

        String fileId = stagingService.stage(accepted, "accepted.csv");
        assertThrows(BusinessException.class, () -> stagingService.stage(rejected, "rejected.csv"));
        stagingService.release(fileId);
    }

    @Test
    void enforcesCumulativeByteQuotaAtTheBoundaryAndReleaseRestoresCapacity() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        System.setProperty("chat2db.task.import.staging.max-bytes", "100");
        System.setProperty("chat2db.task.import.staging.max-files", "10");
        System.setProperty("chat2db.task.import.staging.max-total-bytes", "8");
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File first = Files.writeString(tempDirectory.resolve("first.csv"), "1234").toFile();
        File second = Files.writeString(tempDirectory.resolve("second.csv"), "5678").toFile();
        File replacement = Files.writeString(tempDirectory.resolve("replacement.csv"), "x").toFile();

        String firstId = stagingService.stage(first, "first.csv");
        String secondId = stagingService.stage(second, "second.csv");
        assertThrows(BusinessException.class, () -> stagingService.stage(replacement, "replacement.csv"));

        assertTrue(stagingService.releaseUnclaimed(firstId));
        String replacementId = stagingService.stage(replacement, "replacement.csv");

        stagingService.release(secondId);
        stagingService.release(replacementId);
    }

    @Test
    void enforcesFileCountQuotaAndReleaseRestoresCapacity() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        System.setProperty("chat2db.task.import.staging.max-bytes", "100");
        System.setProperty("chat2db.task.import.staging.max-files", "2");
        System.setProperty("chat2db.task.import.staging.max-total-bytes", "100");
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("count.csv"), "x").toFile();

        String firstId = stagingService.stage(source, "first.csv");
        String secondId = stagingService.stage(source, "second.csv");
        assertThrows(BusinessException.class, () -> stagingService.stage(source, "third.csv"));

        assertTrue(stagingService.releaseUnclaimed(firstId));
        String thirdId = stagingService.stage(source, "third.csv");

        stagingService.release(secondId);
        stagingService.release(thirdId);
    }

    @Test
    void concurrentStagesCannotCrossTheConfiguredFileCountQuota() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        System.setProperty("chat2db.task.import.staging.max-bytes", "100");
        System.setProperty("chat2db.task.import.staging.max-files", "1");
        System.setProperty("chat2db.task.import.staging.max-total-bytes", "100");
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File first = Files.writeString(tempDirectory.resolve("concurrent-first.csv"), "1234").toFile();
        File second = Files.writeString(tempDirectory.resolve("concurrent-second.csv"), "5678").toFile();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = executor.submit(() -> stageAfter(start, stagingService, first, "first.csv"));
            Future<String> secondResult = executor.submit(
                    () -> stageAfter(start, stagingService, second, "second.csv"));
            start.countDown();

            String firstId = firstResult.get();
            String secondId = secondResult.get();
            assertEquals(1, (firstId == null ? 0 : 1) + (secondId == null ? 0 : 1));
            stagingService.release(firstId == null ? secondId : firstId);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clientReleaseAndTaskClaimRaceNeverDeletesAClaimedFile() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("claim.csv"), "id\n1\n").toFile();
        String fileId = stagingService.stage(source, "claim.csv");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> claimed = executor.submit(() -> {
                start.await();
                try {
                    stagingService.claimForTask(fileId);
                    return true;
                } catch (BusinessException missingAfterRelease) {
                    return false;
                }
            });
            Future<Boolean> released = executor.submit(() -> {
                start.await();
                return stagingService.releaseUnclaimed(fileId);
            });
            start.countDown();

            boolean claimWon = claimed.get();
            boolean releaseWon = released.get();
            assertEquals(!claimWon, releaseWon);
            if (claimWon) {
                assertTrue(stagingService.resolve(fileId).isFile());
                stagingService.release(fileId);
            } else {
                assertThrows(BusinessException.class, () -> stagingService.resolve(fileId));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clientReleaseLeavesClaimedFileForTaskCompletion() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("claimed.csv"), "id\n1\n").toFile();
        String fileId = stagingService.stage(source, "claimed.csv");
        stagingService.claimForTask(fileId);

        assertFalse(stagingService.releaseUnclaimed(fileId));
        assertTrue(stagingService.resolve(fileId).isFile());

        stagingService.release(fileId);
        assertThrows(BusinessException.class, () -> stagingService.resolve(fileId));
    }

    @Test
    void stagedFileCanBeClaimedByOnlyOneTask() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("single-consumer.csv"), "id\n1\n").toFile();
        String fileId = stagingService.stage(source, "single-consumer.csv");

        stagingService.claimForTask(fileId);

        assertThrows(BusinessException.class, () -> stagingService.claimForTask(fileId));
        assertFalse(stagingService.releaseUnclaimed(fileId));
        assertTrue(stagingService.resolve(fileId).isFile());
        stagingService.release(fileId);
    }

    @Test
    void claimedStateSurvivesServiceRecreation() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService firstService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("restart-safe.csv"), "id\n1\n").toFile();
        String fileId = firstService.stage(source, "restart-safe.csv");
        firstService.claimForTask(fileId);

        ImportFileStagingService restartedService = new ImportFileStagingService();

        assertFalse(restartedService.releaseUnclaimed(fileId));
        assertThrows(BusinessException.class, () -> restartedService.claimForTask(fileId));
        assertTrue(restartedService.resolve(fileId).isFile());
        restartedService.release(fileId);
        assertThrows(BusinessException.class, () -> restartedService.resolve(fileId));
    }

    @Test
    void claimedFileDoesNotExpireWhileAnAsynchronousTaskOwnsIt() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("long-running.csv"), "id\n1\n").toFile();
        String fileId = stagingService.stage(source, "long-running.csv");
        Path stagedFile = stagingService.resolve(fileId).toPath();
        Path claimMarker = stagedFile.resolveSibling(fileId + ".claimed");
        stagingService.claimForTask(fileId);
        // Older than the 24h unclaimed sweep, still well inside the 7d claimed retention: a task
        // that owns the file must survive the unclaimed cleanup.
        FileTime longRunningTimestamp = FileTime.from(Instant.now().minus(Duration.ofDays(2)));
        Files.setLastModifiedTime(stagedFile, longRunningTimestamp);
        Files.setLastModifiedTime(claimMarker, longRunningTimestamp);

        assertTrue(stagingService.resolve(fileId).isFile());
        assertTrue(Files.isRegularFile(claimMarker));

        stagingService.release(fileId);
        assertFalse(Files.exists(stagedFile));
        assertFalse(Files.exists(claimMarker));
    }

    @Test
    void orphanedClaimMarkerIsReclaimedAfterTheClaimedRetention() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("orphaned.csv"), "id\n1\n").toFile();
        String fileId = stagingService.stage(source, "orphaned.csv");
        Path stagedFile = stagingService.resolve(fileId).toPath();
        Path claimMarker = stagedFile.resolveSibling(fileId + ".claimed");
        stagingService.claimForTask(fileId);
        // The owning task record vanished, so nothing will ever call release() for this file.
        FileTime orphanedTimestamp = FileTime.from(Instant.now().minus(Duration.ofDays(8)));
        Files.setLastModifiedTime(stagedFile, orphanedTimestamp);
        Files.setLastModifiedTime(claimMarker, orphanedTimestamp);

        assertThrows(BusinessException.class, () -> stagingService.resolve(fileId));
        assertFalse(Files.exists(stagedFile));
        assertFalse(Files.exists(claimMarker));
    }

    private static String stageAfter(CountDownLatch start, ImportFileStagingService stagingService,
            File source, String fileName) throws InterruptedException {
        start.await();
        try {
            return stagingService.stage(source, fileName);
        } catch (BusinessException quotaExceeded) {
            return null;
        }
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
