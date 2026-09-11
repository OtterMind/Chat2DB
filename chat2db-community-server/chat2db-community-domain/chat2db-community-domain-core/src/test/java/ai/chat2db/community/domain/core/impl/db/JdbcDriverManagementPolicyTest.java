package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDriverManagementPolicyTest {

    @TempDir
    Path tempDirectory;

    @Test
    void allowsDesktopAndCommunityWebButRejectsCommercialWeb() {
        assertTrue(JdbcDriverManagementPolicy.isSupported(true, false));
        assertTrue(JdbcDriverManagementPolicy.isSupported(false, true));
        assertFalse(JdbcDriverManagementPolicy.isSupported(false, false));
    }

    @Test
    void promotesOpaqueUploadTokensWithoutOverwritingManagedDrivers() throws Exception {
        Path stagingDirectory = Files.createDirectories(tempDirectory.resolve("staging"));
        Path driverDirectory = Files.createDirectories(tempDirectory.resolve("drivers"));
        String uploadId = "0123456789abcdef0123456789abcdef";
        Files.writeString(stagingDirectory.resolve(uploadId + ".upload"), "driver");

        JdbcDriverManagementPolicy.PromotedDrivers promoted =
                JdbcDriverManagementPolicy.promoteUploadedDrivers(
                        List.of(uploadId + ":mysql.jar"), stagingDirectory, driverDirectory);

        assertEquals("mysql.jar", promoted.jdbcDriver());
        assertEquals("driver", Files.readString(driverDirectory.resolve("mysql.jar")));
        assertFalse(Files.exists(stagingDirectory.resolve(uploadId + ".upload")));

        promoted.rollback();
        assertFalse(Files.exists(driverDirectory.resolve("mysql.jar")));
    }

    @Test
    void rejectsCollisionAndPreservesExistingManagedDriver() throws Exception {
        Path stagingDirectory = Files.createDirectories(tempDirectory.resolve("staging"));
        Path driverDirectory = Files.createDirectories(tempDirectory.resolve("drivers"));
        String uploadId = "0123456789abcdef0123456789abcdef";
        Files.writeString(stagingDirectory.resolve(uploadId + ".upload"), "replacement");
        Files.writeString(driverDirectory.resolve("mysql.jar"), "existing");

        assertUploadFailure(List.of(uploadId + ":mysql.jar"), stagingDirectory, driverDirectory);

        assertEquals("existing", Files.readString(driverDirectory.resolve("mysql.jar")));
        assertFalse(Files.exists(stagingDirectory.resolve(uploadId + ".upload")));
    }

    @Test
    void rejectsMissingMalformedAndEmptyTokens() throws Exception {
        Path stagingDirectory = Files.createDirectories(tempDirectory.resolve("staging"));
        Path driverDirectory = Files.createDirectories(tempDirectory.resolve("drivers"));
        assertUploadFailure(List.of("0123456789abcdef0123456789abcdef:missing.jar"),
                stagingDirectory, driverDirectory);
        assertUploadFailure(List.of("../outside.jar"), stagingDirectory, driverDirectory);
        assertUploadFailure(List.of("0123456789abcdef0123456789abcdef:driver.txt"),
                stagingDirectory, driverDirectory);
        assertUploadFailure(List.of(), stagingDirectory, driverDirectory);
    }

    @Test
    void concurrentSameNamePromotionsNeverOverwriteTheWinner() throws Exception {
        Path stagingDirectory = Files.createDirectories(tempDirectory.resolve("staging"));
        Path driverDirectory = Files.createDirectories(tempDirectory.resolve("drivers"));
        String firstId = "0123456789abcdef0123456789abcdef";
        String secondId = "fedcba9876543210fedcba9876543210";
        Files.writeString(stagingDirectory.resolve(firstId + ".upload"), "first");
        Files.writeString(stagingDirectory.resolve(secondId + ".upload"), "second");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> promoteAfter(start, firstId, stagingDirectory, driverDirectory));
            var second = executor.submit(() -> promoteAfter(start, secondId, stagingDirectory, driverDirectory));
            start.countDown();
            Object firstResult = first.get();
            Object secondResult = second.get();

            long successes = List.of(firstResult, secondResult).stream()
                    .filter(JdbcDriverManagementPolicy.PromotedDrivers.class::isInstance)
                    .count();
            assertEquals(1, successes);
            assertTrue(List.of("first", "second").contains(Files.readString(driverDirectory.resolve("shared.jar"))));
            Object failure = firstResult instanceof BusinessException ? firstResult : secondResult;
            assertInstanceOf(BusinessException.class, failure);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fallsBackToCreateNewCopyWhenHardLinksAreUnavailable() throws Exception {
        Path source = tempDirectory.resolve("staged.upload");
        Path target = tempDirectory.resolve("driver.jar");
        Files.writeString(source, "driver");

        JdbcDriverManagementPolicy.moveWithoutReplacement(source, target, (link, existing) -> {
            throw new FileSystemException(link.toString(), existing.toString(), "hard links unavailable");
        });

        assertEquals("driver", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    private Object promoteAfter(CountDownLatch start, String uploadId, Path stagingDirectory,
                                Path driverDirectory) throws Exception {
        start.await();
        try {
            return JdbcDriverManagementPolicy.promoteUploadedDrivers(
                    List.of(uploadId + ":shared.jar"), stagingDirectory, driverDirectory);
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private void assertUploadFailure(List<String> uploadTokens, Path stagingDirectory, Path driverDirectory) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> JdbcDriverManagementPolicy.promoteUploadedDrivers(
                        uploadTokens, stagingDirectory, driverDirectory));
        assertEquals("jdbc.driver.uploadFailed", exception.getCode());
    }
}
