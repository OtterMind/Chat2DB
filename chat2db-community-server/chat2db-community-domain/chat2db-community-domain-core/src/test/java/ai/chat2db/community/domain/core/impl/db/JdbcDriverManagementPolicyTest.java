package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
        BusinessException traversalFailure = assertUploadFailure(
                List.of("0123456789abcdef0123456789abcdef:...jar"),
                stagingDirectory, driverDirectory);
        assertEquals("invalid driver upload token", traversalFailure.getArgs()[0]);
        assertUploadFailure(List.of(), stagingDirectory, driverDirectory);
    }

    @Test
    void acceptsSafeDottedUnicodeAndLiteralEncodedSeparatorNames() throws Exception {
        Path stagingDirectory = Files.createDirectories(tempDirectory.resolve("staging"));
        Path driverDirectory = Files.createDirectories(tempDirectory.resolve("drivers"));
        String firstId = "0123456789abcdef0123456789abcdef";
        String secondId = "123456789abcdef0123456789abcdef0";
        String thirdId = "23456789abcdef0123456789abcdef01";
        String unicodeName = "\u9a71\u52a8-1.2.jar";
        Files.writeString(stagingDirectory.resolve(firstId + ".upload"), "dotted");
        Files.writeString(stagingDirectory.resolve(secondId + ".upload"), "unicode");
        Files.writeString(stagingDirectory.resolve(thirdId + ".upload"), "encoded");

        JdbcDriverManagementPolicy.PromotedDrivers promoted =
                JdbcDriverManagementPolicy.promoteUploadedDrivers(
                        List.of(firstId + ":driver..jar", secondId + ":" + unicodeName,
                                thirdId + ":literal%2F.jar"),
                        stagingDirectory, driverDirectory);

        assertEquals("driver..jar," + unicodeName + ",literal%2F.jar", promoted.jdbcDriver());
        assertEquals("dotted", Files.readString(driverDirectory.resolve("driver..jar")));
        assertEquals("unicode", Files.readString(driverDirectory.resolve(unicodeName)));
        assertEquals("encoded", Files.readString(driverDirectory.resolve("literal%2F.jar")));
    }

    @Test
    void rejectsCommaDelimitedDriverNameEvenWhenTheStagedFileExists() throws Exception {
        Path stagingDirectory = Files.createDirectories(tempDirectory.resolve("staging"));
        Path driverDirectory = Files.createDirectories(tempDirectory.resolve("drivers"));
        String uploadId = "0123456789abcdef0123456789abcdef";
        Files.writeString(stagingDirectory.resolve(uploadId + ".upload"), "driver");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> JdbcDriverManagementPolicy.promoteUploadedDrivers(
                        List.of(uploadId + ":first,second.jar"), stagingDirectory, driverDirectory));

        assertEquals("jdbc.driver.uploadFailed", exception.getCode());
        assertEquals("invalid driver upload token", exception.getArgs()[0]);
        assertFalse(Files.exists(driverDirectory.resolve("first,second.jar")));
    }

    @Test
    void rejectsSymlinkedStagedUploadWithoutPublishingItsTarget() throws Exception {
        assumeSymbolicLinksAreAvailable();
        Path stagingDirectory = Files.createDirectories(tempDirectory.resolve("staging"));
        Path driverDirectory = Files.createDirectories(tempDirectory.resolve("drivers"));
        Path outsideFile = Files.writeString(tempDirectory.resolve("outside.jar"), "outside");
        String uploadId = "0123456789abcdef0123456789abcdef";
        Path stagedFile = stagingDirectory.resolve(uploadId + ".upload");
        Files.createSymbolicLink(stagedFile, outsideFile);

        assertUploadFailure(List.of(uploadId + ":mysql.jar"), stagingDirectory, driverDirectory);

        assertEquals("outside", Files.readString(outsideFile));
        assertFalse(Files.exists(stagedFile, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(driverDirectory.resolve("mysql.jar"), LinkOption.NOFOLLOW_LINKS));
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
    void copiesRegularFileAndRemovesTheStagingEntry() throws Exception {
        Path source = tempDirectory.resolve("staged.upload");
        Path target = tempDirectory.resolve("driver.jar");
        Files.writeString(source, "driver");

        JdbcDriverManagementPolicy.moveWithoutReplacement(source, target);

        assertEquals("driver", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    @Test
    void rejectsSymlinkSubstitutedBetweenValidationAndPublication() throws Exception {
        assumeSymbolicLinksAreAvailable();
        Path source = tempDirectory.resolve("staged.upload");
        Path target = tempDirectory.resolve("driver.jar");
        Path outsideFile = Files.writeString(tempDirectory.resolve("outside.jar"), "outside");
        Files.writeString(source, "driver");
        assertTrue(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS));
        Files.delete(source);
        Files.createSymbolicLink(source, outsideFile);

        assertThrows(IOException.class,
                () -> JdbcDriverManagementPolicy.moveWithoutReplacement(source, target));

        assertEquals("outside", Files.readString(outsideFile));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
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

    private BusinessException assertUploadFailure(List<String> uploadTokens, Path stagingDirectory,
                                                  Path driverDirectory) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> JdbcDriverManagementPolicy.promoteUploadedDrivers(
                        uploadTokens, stagingDirectory, driverDirectory));
        assertEquals("jdbc.driver.uploadFailed", exception.getCode());
        return exception;
    }

    private void assumeSymbolicLinksAreAvailable() throws Exception {
        Path target = Files.writeString(tempDirectory.resolve("symlink-probe-target"), "probe");
        Path link = tempDirectory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
        }
    }
}
