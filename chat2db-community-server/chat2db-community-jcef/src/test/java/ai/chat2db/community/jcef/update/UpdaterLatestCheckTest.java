package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterLatestCheckTest {

    @Test
    void checkReadsOnlyLatestVersionDocument(@TempDir Path temporaryDirectory) throws Exception {
        withCheckPaths(temporaryDirectory, "{\"version\":\"5.3.0\",\"files\":[]}", latest("5.3.1"), updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isNeedsUpdate());
            assertEquals("Test release notes", result.getReleaseNotes());
            assertNull(result.getRemoteMetadata());
        });
    }

    @Test
    void checkReportsNotAvailableWhenLatestVersionIsNotNewer(@TempDir Path temporaryDirectory) throws Exception {
        withCheckPaths(temporaryDirectory, "{\"version\":\"5.3.1\",\"files\":[]}", latest("5.3.1"), updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertFalse(result.isNeedsUpdate());
            assertFalse(result.isCheckFailed());
        });
    }

    @Test
    void checkReportsFailureWhenLatestEndpointCannotBeRead(@TempDir Path temporaryDirectory) throws Exception {
        withCheckPaths(temporaryDirectory, "{\"version\":\"5.3.0\",\"files\":[]}", null, updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertFalse(result.isNeedsUpdate());
        });
    }

    @Test
    void checkReportsFailureForMalformedLatestDocument(@TempDir Path temporaryDirectory) throws Exception {
        withCheckPaths(temporaryDirectory, "{\"version\":\"5.3.0\",\"files\":[]}", "{not-json", updater -> {
            assertTrue(updater.appCheckUpdate().isCheckFailed());
        });
    }

    @Test
    void checkReportsFailureWhenLatestVersionIsBlank(@TempDir Path temporaryDirectory) throws Exception {
        withCheckPaths(temporaryDirectory, "{\"version\":\"5.3.0\",\"files\":[]}", latest(""), updater -> {
            assertTrue(updater.appCheckUpdate().isCheckFailed());
        });
    }

    @Test
    void checkDoesNotCleanTemporaryDownloadsOrBackups(@TempDir Path temporaryDirectory) throws Exception {
        withCheckPaths(temporaryDirectory, "{\"version\":\"5.3.0\",\"files\":[]}", latest("5.3.1"), updater -> {
            Path temporaryDownload = temporaryDirectory.resolve("downloads/unfinished.part");
            Files.createDirectories(temporaryDownload.getParent());
            Files.writeString(temporaryDownload, "keep");
            Path backupSession = Files.createDirectories(temporaryDirectory.resolve(".chat2db-update-backups/old-session"));
            Files.writeString(backupSession.resolve(".owner-pid"), "0");
            downloadedFiles(updater).put("unfinished", temporaryDownload);

            assertTrue(updater.appCheckUpdate().isNeedsUpdate());

            assertTrue(Files.exists(temporaryDownload));
            assertTrue(Files.exists(backupSession));
            assertTrue(Files.exists(backupSession.resolve(".owner-pid")));
            downloadedFiles(updater).clear();
        });
    }

    @Test
    void checkDoesNotRenameAnUnreadableLocalVersionFile(@TempDir Path temporaryDirectory) throws Exception {
        withCheckPaths(temporaryDirectory, "{not-json", latest("5.3.1"), updater -> {
            Path localVersion = temporaryDirectory.resolve("local_version.json");

            assertTrue(updater.appCheckUpdate().isNeedsUpdate());

            assertTrue(Files.exists(localVersion));
            assertEquals("{not-json", Files.readString(localVersion));
            try (var paths = Files.list(temporaryDirectory)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("local_version.json.corrupted_")));
            }
        });
    }

    @Test
    void concurrentCheckCallsWaitForTheSameInFlightCheck() throws Exception {
        Updater updater = Updater.getInstance();
        CompletableFuture<Updater.CheckResult> inFlight = new CompletableFuture<>();
        Field activeCheckField = field("activeCheck");
        Object originalActiveCheck = activeCheckField.get(updater);
        activeCheckField.set(updater, inFlight);
        CountDownLatch started = new CountDownLatch(2);
        AtomicReference<Updater.CheckResult> firstResult = new AtomicReference<>();
        AtomicReference<Updater.CheckResult> secondResult = new AtomicReference<>();
        try {
            Thread first = waitingCheckThread(updater, started, firstResult);
            Thread second = waitingCheckThread(updater, started, secondResult);
            first.start();
            second.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));

            Updater.CheckResult expected = new Updater.CheckResult(false, null, null, null, false, null);
            inFlight.complete(expected);
            first.join(5_000);
            second.join(5_000);

            assertSame(expected, firstResult.get());
            assertSame(expected, secondResult.get());
        } finally {
            activeCheckField.set(updater, originalActiveCheck);
        }
    }

    private static Thread waitingCheckThread(Updater updater, CountDownLatch started,
                                             AtomicReference<Updater.CheckResult> result) {
        return new Thread(() -> {
            started.countDown();
            result.set(updater.appCheckUpdate());
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Path> downloadedFiles(Updater updater) throws Exception {
        Field field = field("downloadedFilesMap");
        return (Map<String, Path>) field.get(updater);
    }

    private static void withCheckPaths(Path directory, String localVersionContent, String latestVersionContent,
                                       ThrowingConsumer<Updater> test) throws Exception {
        Updater updater = Updater.getInstance();
        Path localVersion = directory.resolve("local_version.json");
        Files.writeString(localVersion, localVersionContent);
        Path latestVersion = directory.resolve("latest_version.json");
        String latestVersionUrl = latestVersion.toUri().toString();
        if (latestVersionContent != null) {
            Files.writeString(latestVersion, latestVersionContent);
        } else {
            latestVersionUrl = directory.resolve("missing-latest_version.json").toUri().toString();
        }

        Field appDirectoryField = field("APP_DIR");
        Field localVersionField = field("LOCAL_VERSION_FILE");
        Field temporaryDirectoryField = field("TMP_DIR");
        Field latestVersionUrlField = field("LATEST_VERSION_INFO_URL");
        Object originalAppDirectory = appDirectoryField.get(updater);
        Object originalLocalVersion = localVersionField.get(updater);
        Object originalTemporaryDirectory = temporaryDirectoryField.get(updater);
        Object originalLatestVersionUrl = latestVersionUrlField.get(updater);
        try {
            appDirectoryField.set(updater, directory);
            localVersionField.set(updater, localVersion);
            temporaryDirectoryField.set(updater, directory.resolve("downloads"));
            latestVersionUrlField.set(updater, latestVersionUrl);
            test.accept(updater);
        } finally {
            downloadedFiles(updater).clear();
            appDirectoryField.set(updater, originalAppDirectory);
            localVersionField.set(updater, originalLocalVersion);
            temporaryDirectoryField.set(updater, originalTemporaryDirectory);
            latestVersionUrlField.set(updater, originalLatestVersionUrl);
        }
    }

    private static String latest(String version) {
        return """
                {
                  "latestVersion": "%s",
                  "metadataUrl": "https://cdn.chat2db-ai.com/community/updates/5.3.1/version.json",
                  "releaseNotes": "Test release notes"
                }
                """.formatted(version);
    }

    private static Field field(String name) throws Exception {
        Field field = Updater.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
