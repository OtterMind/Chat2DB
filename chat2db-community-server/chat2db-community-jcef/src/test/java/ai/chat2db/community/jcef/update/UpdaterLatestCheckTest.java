package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterLatestCheckTest {

    @Test
    void checkReadsOnlyDiscoveryFieldsAndRetainsManifestBytes(@TempDir Path temporaryDirectory) throws Exception {
        String manifest = githubManifest("5.3.2", "Test release notes", false);
        withUpdater(temporaryDirectory, manifest, updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isNeedsUpdate());
            assertEquals("Test release notes", result.getReleaseNotes());
            assertEquals("https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.2", result.getReleasePageUrl());
            assertNotNull(result.getAvailableSnapshot());
            assertArrayEquals(manifest.getBytes(StandardCharsets.UTF_8), result.getAvailableSnapshot().exactBytes());
            assertNull(result.getRemoteMetadata());
        });
    }

    @Test
    void checkReportsNotAvailableWhenLatestVersionIsNotNewer(@TempDir Path temporaryDirectory) throws Exception {
        withUpdater(temporaryDirectory, githubManifest("5.3.1", "Notes", false), updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertFalse(result.isNeedsUpdate());
            assertFalse(result.isCheckFailed());
        });
    }

    @Test
    void checkReportsFailureWhenLatestEndpointCannotBeRead(@TempDir Path temporaryDirectory) throws Exception {
        FakeUpdateSource source = new FakeUpdateSource().manifestException(new IOException("network error"));
        withUpdater(temporaryDirectory, source, updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertFalse(result.isNeedsUpdate());
            assertEquals(UpdateFailureStage.CHECK, result.getFailureStage());
            assertEquals(UpdateFailureReason.NETWORK, result.getFailureReason());
        });
    }

    @Test
    void checkReportsFailureForMalformedLatestDocument(@TempDir Path temporaryDirectory) throws Exception {
        withUpdater(temporaryDirectory, "{not-json", updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertEquals(UpdateFailureStage.CHECK, result.getFailureStage());
            assertEquals(UpdateFailureReason.INVALID_MANIFEST, result.getFailureReason());
        });
    }

    @Test
    void checkReportsFailureWhenLatestVersionIsBlank(@TempDir Path temporaryDirectory) throws Exception {
        withUpdater(temporaryDirectory, githubManifest("", "Notes", false), updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertEquals(UpdateFailureReason.INVALID_MANIFEST, result.getFailureReason());
        });
    }

    @Test
    void checkRejectsRemoteManifestWithForceUpdateTrue(@TempDir Path temporaryDirectory) throws Exception {
        withUpdater(temporaryDirectory, githubManifest("5.3.2", "Notes", true), updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertEquals(UpdateFailureReason.INVALID_MANIFEST, result.getFailureReason());
        });
    }

    @Test
    void checkRejectsRemoteManifestWithMissingForceUpdate(@TempDir Path temporaryDirectory) throws Exception {
        String manifest = """
                {
                  "version": "5.3.2",
                  "files": []
                }
                """;
        withUpdater(temporaryDirectory, manifest, updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertEquals(UpdateFailureReason.INVALID_MANIFEST, result.getFailureReason());
        });
    }

    @Test
    void checkRejectsOversizedReleaseNotes(@TempDir Path temporaryDirectory) throws Exception {
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < 64 * 1024 + 1; i++) {
            notes.append('a');
        }
        withUpdater(temporaryDirectory, githubManifest("5.3.2", notes.toString(), false), updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertEquals(UpdateFailureReason.INVALID_MANIFEST, result.getFailureReason());
        });
    }

    @Test
    void checkRejectsMismatchedReleasePageUrl(@TempDir Path temporaryDirectory) throws Exception {
        String manifest = """
                {
                  "version": "5.3.2",
                  "releaseNotes": "Notes",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.3",
                  "forceUpdate": false,
                  "files": []
                }
                """;
        withUpdater(temporaryDirectory, manifest, updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isCheckFailed());
            assertEquals(UpdateFailureReason.INVALID_MANIFEST, result.getFailureReason());
        });
    }

    @Test
    void checkDoesNotCleanTemporaryDownloadsOrBackups(@TempDir Path temporaryDirectory) throws Exception {
        withUpdater(temporaryDirectory, githubManifest("5.3.2", "Notes", false), updater -> {
            Path temporaryDownload = temporaryDirectory.resolve("downloads/unfinished.part");
            Files.createDirectories(temporaryDownload.getParent());
            Files.writeString(temporaryDownload, "keep");
            Path backupSession = Files.createDirectories(temporaryDirectory.resolve(".chat2db-update-backups/old-session"));
            Files.writeString(backupSession.resolve(".owner-pid"), "0");
            updater.operationCoordinator().rememberDownloadedFile("unfinished", temporaryDownload);

            assertTrue(updater.appCheckUpdate().isNeedsUpdate());

            assertTrue(Files.exists(temporaryDownload));
            assertTrue(Files.exists(backupSession));
            assertTrue(Files.exists(backupSession.resolve(".owner-pid")));
            updater.operationCoordinator().clearDownloadedFiles();
        });
    }

    @Test
    void checkDoesNotRenameAnUnreadableLocalVersionFile(@TempDir Path temporaryDirectory) throws Exception {
        withUpdater(temporaryDirectory, "{not-json", githubManifest("5.3.2", "Notes", false), updater -> {
            Updater.CheckResult result = updater.appCheckUpdate();

            assertTrue(result.isNeedsUpdate());

            Path localVersion = temporaryDirectory.resolve("local_version.json");
            assertTrue(Files.exists(localVersion));
            assertEquals("{not-json", Files.readString(localVersion));
            try (var paths = Files.list(temporaryDirectory)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("local_version.json.corrupted_")));
            }
        });
    }

    @Test
    void concurrentCheckCallsWaitForTheSameInFlightCheck(@TempDir Path temporaryDirectory) throws Exception {
        Updater updater = new Updater(new FakeUpdateSource().manifest("{}"), temporaryDirectory,
                temporaryDirectory.resolve("local_version.json"), temporaryDirectory.resolve("downloads"), System::nanoTime);
        UpdateOperationCoordinator.CheckOperation inFlight = updater.operationCoordinator().beginCheck();
        assertTrue(inFlight.started());
        CountDownLatch started = new CountDownLatch(2);
        AtomicReference<Updater.CheckResult> firstResult = new AtomicReference<>();
        AtomicReference<Updater.CheckResult> secondResult = new AtomicReference<>();
        Thread first = waitingCheckThread(updater, started, firstResult);
        Thread second = waitingCheckThread(updater, started, secondResult);
        first.start();
        second.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        Updater.CheckResult expected = new Updater.CheckResult(false, null, null, null, false, null, null, null, null, null);
        updater.operationCoordinator().completeCheck(expected);
        first.join(5_000);
        second.join(5_000);

        assertSame(expected, firstResult.get());
        assertSame(expected, secondResult.get());
    }

    @Test
    void unexpiredSnapshotAvoidsSecondDiscoveryRequest(@TempDir Path temporaryDirectory) throws Exception {
        String localJar = "chat2db-community.jar";
        byte[] jarBytes = "jar".getBytes(StandardCharsets.UTF_8);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", localJar, sha256(jarBytes), jarBytes.length))
                .payloads(Map.of(localJar, jarBytes));
        AtomicLong clock = new AtomicLong(0);
        source.fetchedAtNanos(clock.get());
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), clock::get);
        setReleaseProfile();

        updater.appCheckUpdate();
        assertEquals(1, source.fetchCount());

        clock.addAndGet(AvailableSnapshot.TTL_NANOS - 1);
        updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());

        assertEquals(1, source.fetchCount());
    }

    @Test
    void downloadUsesManifestVersionInsteadOfPayloadId(@TempDir Path temporaryDirectory) throws Exception {
        byte[] jarBytes = "jar".getBytes(StandardCharsets.UTF_8);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.4.0", "chat2db-community-server", "chat2db-community.jar",
                        sha256(jarBytes), jarBytes.length))
                .payloads(Map.of("chat2db-community.jar", jarBytes));
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), System::nanoTime);
        Files.writeString(temporaryDirectory.resolve("local_version.json"), "{\"version\":\"5.3.1\",\"files\":[]}");
        setReleaseProfile();

        updater.appCheckUpdate();
        updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());

        assertNotNull(source.lastPayloadRequest());
        assertEquals("5.4.0", source.lastPayloadRequest().version());
        assertEquals("chat2db-community.jar", source.lastPayloadRequest().assetName());
    }

    @Test
    void resumesFromSavedPartialWhenContentRangeMatches(@TempDir Path temporaryDirectory) throws Exception {
        byte[] jarBytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", "chat2db-community.jar", sha256(jarBytes), jarBytes.length))
                .payloads(Map.of("chat2db-community.jar", jarBytes));
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), System::nanoTime);
        Files.writeString(temporaryDirectory.resolve("local_version.json"), "{\"version\":\"5.3.1\",\"files\":[]}");
        Path partial = temporaryDirectory.resolve("downloads/chat2db-community.jar.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, java.util.Arrays.copyOf(jarBytes, 4));
        setReleaseProfile();

        updater.appCheckUpdate();
        updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());

        assertEquals(1, source.payloadRequestCount());
        assertArrayEquals(jarBytes, Files.readAllBytes(temporaryDirectory.resolve("downloads/chat2db-community.jar")));
        assertFalse(Files.exists(partial));
    }

    @Test
    void restartsFromZeroWhenServerIgnoresRange(@TempDir Path temporaryDirectory) throws Exception {
        byte[] jarBytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", "chat2db-community.jar", sha256(jarBytes), jarBytes.length))
                .payloads(Map.of("chat2db-community.jar", jarBytes))
                .payloadResponseMode(FakeUpdateSource.PayloadResponseMode.RANGE_IGNORED);
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), System::nanoTime);
        Files.writeString(temporaryDirectory.resolve("local_version.json"), "{\"version\":\"5.3.1\",\"files\":[]}");
        Path partial = temporaryDirectory.resolve("downloads/chat2db-community.jar.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, java.util.Arrays.copyOf(jarBytes, 4));
        setReleaseProfile();

        updater.appCheckUpdate();
        updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());

        assertEquals(2, source.payloadRequestCount());
        assertEquals(0L, source.lastPayloadRequest().rangeOffset());
        assertArrayEquals(jarBytes, Files.readAllBytes(temporaryDirectory.resolve("downloads/chat2db-community.jar")));
    }

    @Test
    void deleteOnlyUpdateReleasesTheDownloadGuardAndInstalls(@TempDir Path temporaryDirectory) throws Exception {
        String localMetadata = """
                {"version":"5.3.1","files":[{"id":"obsolete","localTargetName":"obsolete.jar"}]}
                """;
        String remoteManifest = """
                {
                  "version":"5.3.2",
                  "releaseNotes":"Remove obsolete file",
                  "releasePageUrl":"https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.2",
                  "forceUpdate":false,
                  "files":[{"id":"obsolete","localTargetName":"obsolete.jar","deleted":true}]
                }
                """;
        Path obsoleteFile = temporaryDirectory.resolve("obsolete.jar");
        Files.writeString(obsoleteFile, "obsolete");

        withUpdater(temporaryDirectory, localMetadata, remoteManifest, updater -> {
            setReleaseProfile();
            updater.appCheckUpdate();

            assertTrue(updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult()).isEmpty());
            assertTrue(updater.triggerInstallation());
            assertFalse(Files.exists(obsoleteFile));
        });
    }

    @Test
    void keepLocalOnlyUpdateReleasesTheDownloadGuardAndInstalls(@TempDir Path temporaryDirectory) throws Exception {
        byte[] jarBytes = "already-current".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(jarBytes);
        String localMetadata = """
                {"version":"5.3.1","files":[{"id":"chat2db-community.jar","localTargetName":"chat2db-community.jar","sha256":"%s","type":"jar"}]}
                """.formatted(checksum);
        Path localJar = temporaryDirectory.resolve("chat2db-community.jar");
        Files.write(localJar, jarBytes);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", "chat2db-community.jar", checksum, jarBytes.length))
                .payloads(Map.of("chat2db-community.jar", jarBytes));

        withUpdater(temporaryDirectory, localMetadata, source, updater -> {
            setReleaseProfile();
            updater.appCheckUpdate();

            assertTrue(updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult()).isEmpty());
            assertEquals(0, source.payloadRequestCount());
            assertTrue(updater.triggerInstallation());
            assertArrayEquals(jarBytes, Files.readAllBytes(localJar));
        });
    }

    @Test
    void restartsFromZeroWhenContentRangeDoesNotMatchSavedPartial(@TempDir Path temporaryDirectory) throws Exception {
        byte[] jarBytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", "chat2db-community.jar", sha256(jarBytes), jarBytes.length))
                .payloads(Map.of("chat2db-community.jar", jarBytes))
                .payloadResponseMode(FakeUpdateSource.PayloadResponseMode.WRONG_CONTENT_RANGE);
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), System::nanoTime);
        Files.writeString(temporaryDirectory.resolve("local_version.json"), "{\"version\":\"5.3.1\",\"files\":[]}");
        Path partial = temporaryDirectory.resolve("downloads/chat2db-community.jar.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, java.util.Arrays.copyOf(jarBytes, 4));
        setReleaseProfile();

        updater.appCheckUpdate();
        updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());

        assertEquals(2, source.payloadRequestCount());
        assertEquals(0L, source.lastPayloadRequest().rangeOffset());
        assertArrayEquals(jarBytes, Files.readAllBytes(temporaryDirectory.resolve("downloads/chat2db-community.jar")));
        assertFalse(Files.exists(partial));
        assertNull(updater.currentCheckResult().getFailureStage());
    }

    @Test
    void rejectsTruncatedResumedResponse(@TempDir Path temporaryDirectory) throws Exception {
        byte[] jarBytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", "chat2db-community.jar", sha256(jarBytes), jarBytes.length))
                .payloads(Map.of("chat2db-community.jar", jarBytes))
                .payloadResponseMode(FakeUpdateSource.PayloadResponseMode.TRUNCATED_PARTIAL);
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), System::nanoTime);
        Files.writeString(temporaryDirectory.resolve("local_version.json"), "{\"version\":\"5.3.1\",\"files\":[]}");
        Path partial = temporaryDirectory.resolve("downloads/chat2db-community.jar.part");
        Files.createDirectories(partial.getParent());
        Files.write(partial, java.util.Arrays.copyOf(jarBytes, 4));
        setReleaseProfile();

        updater.appCheckUpdate();
        IOException exception = assertThrows(IOException.class,
                () -> updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult()));

        assertTrue(exception.getMessage().contains("size does not match metadata"));
        assertFalse(Files.exists(temporaryDirectory.resolve("downloads/chat2db-community.jar")));
        assertEquals(jarBytes.length - 1L, Files.size(partial));
        assertEquals(UpdateFailureStage.DOWNLOAD, updater.currentCheckResult().getFailureStage());
    }

    @Test
    void expiredSnapshotRefreshesBeforePayloadAccess(@TempDir Path temporaryDirectory) throws Exception {
        String localJar = "chat2db-community.jar";
        byte[] jarBytes = "jar".getBytes(StandardCharsets.UTF_8);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", localJar, sha256(jarBytes), jarBytes.length))
                .payloads(Map.of(localJar, jarBytes));
        AtomicLong clock = new AtomicLong(0);
        source.fetchedAtNanos(clock.get());
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), clock::get);
        setReleaseProfile();

        updater.appCheckUpdate();
        assertEquals(1, source.fetchCount());

        clock.addAndGet(AvailableSnapshot.TTL_NANOS + 1);
        updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());

        assertEquals(2, source.fetchCount());
    }

    @Test
    void expiredSameVersionSameBytesRenewsTimestamp(@TempDir Path temporaryDirectory) throws Exception {
        String localJar = "chat2db-community.jar";
        byte[] jarBytes = "jar".getBytes(StandardCharsets.UTF_8);
        String manifest = githubManifestWithJar("5.3.2", localJar, sha256(jarBytes), jarBytes.length);
        AtomicLong clock = new AtomicLong(100);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(manifest)
                .payloads(Map.of(localJar, jarBytes))
                .fetchedAtNanos(clock.get());
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), clock::get);
        setReleaseProfile();

        updater.appCheckUpdate();
        long firstFetchedAt = updater.currentCheckResult().getAvailableSnapshot().fetchedAtNanos();

        clock.addAndGet(AvailableSnapshot.TTL_NANOS + 1);
        source.fetchedAtNanos(clock.get());
        updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());

        assertEquals(2, source.fetchCount());
        long secondFetchedAt = updater.currentCheckResult().getAvailableSnapshot().fetchedAtNanos();
        assertTrue(secondFetchedAt > firstFetchedAt);
    }

    @Test
    void expiredDifferentVersionRequiresFreshDownloadAction(@TempDir Path temporaryDirectory) throws Exception {
        String localJar = "chat2db-community.jar";
        byte[] jarBytes = "jar".getBytes(StandardCharsets.UTF_8);
        String manifestV1 = githubManifestWithJar("5.3.2", localJar, sha256(jarBytes), jarBytes.length);
        String manifestV2 = githubManifestWithJar("5.3.3", localJar, sha256(jarBytes), jarBytes.length);
        AtomicLong clock = new AtomicLong(0);
        FakeUpdateSource source = new FakeUpdateSource().manifest(manifestV1).fetchedAtNanos(clock.get());
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), clock::get);

        updater.appCheckUpdate();
        source.manifest(manifestV2);
        clock.addAndGet(AvailableSnapshot.TTL_NANOS + 1);
        setReleaseProfile();

        try {
            updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());
        } catch (ai.chat2db.community.tools.exception.BusinessException expected) {
            assertEquals("A newer version is available. Please check again before downloading.", expected.getMessage());
            assertEquals("5.3.3", updater.currentCheckResult().getAvailableSnapshot().version());
            return;
        }
        throw new AssertionError("Expected BusinessException for changed version");
    }

    @Test
    void expiredSameVersionDifferentBytesFailsClosed(@TempDir Path temporaryDirectory) throws Exception {
        String localJar = "chat2db-community.jar";
        byte[] jarBytes = "jar".getBytes(StandardCharsets.UTF_8);
        String manifestV1 = githubManifestWithJar("5.3.2", localJar, sha256(jarBytes), jarBytes.length);
        String manifestV2 = githubManifestWithJar("5.3.2", localJar, sha256(jarBytes), jarBytes.length)
                .replace("Test release notes", "Different notes");
        AtomicLong clock = new AtomicLong(0);
        FakeUpdateSource source = new FakeUpdateSource().manifest(manifestV1).fetchedAtNanos(clock.get());
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), clock::get);

        updater.appCheckUpdate();
        source.manifest(manifestV2);
        clock.addAndGet(AvailableSnapshot.TTL_NANOS + 1);
        setReleaseProfile();

        try {
            updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Immutable Release contract violated"));
            assertTrue(updater.currentCheckResult().isCheckFailed());
            assertEquals(UpdateFailureStage.DOWNLOAD, updater.currentCheckResult().getFailureStage());
            return;
        }
        throw new AssertionError("Expected IOException for immutable contract violation");
    }

    @Test
    void failedMandatoryRefreshPerformsNoPayloadRequest(@TempDir Path temporaryDirectory) throws Exception {
        String localJar = "chat2db-community.jar";
        byte[] jarBytes = "jar".getBytes(StandardCharsets.UTF_8);
        AtomicLong clock = new AtomicLong(0);
        FakeUpdateSource source = new FakeUpdateSource()
                .manifest(githubManifestWithJar("5.3.2", localJar, sha256(jarBytes), jarBytes.length))
                .fetchedAtNanos(clock.get());
        Updater updater = new Updater(source, temporaryDirectory, temporaryDirectory.resolve("local_version.json"),
                temporaryDirectory.resolve("downloads"), clock::get);

        updater.appCheckUpdate();
        source.manifestException(new IOException("refresh failed"));
        clock.addAndGet(AvailableSnapshot.TTL_NANOS + 1);
        setReleaseProfile();

        try {
            updater.triggerDownload(new ai.chat2db.community.tools.console.ConsoleResult());
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("refresh failed"));
            assertEquals(2, source.fetchCount());
            return;
        }
        throw new AssertionError("Expected IOException for refresh failure");
    }

    private static Thread waitingCheckThread(Updater updater, CountDownLatch started,
                                             AtomicReference<Updater.CheckResult> result) {
        return new Thread(() -> {
            started.countDown();
            result.set(updater.appCheckUpdate());
        });
    }

    private static void withUpdater(Path directory, String manifestJson, ThrowingConsumer<Updater> test) throws Exception {
        withUpdater(directory, "{\"version\":\"5.3.1\",\"files\":[]}", manifestJson, test);
    }

    private static void withUpdater(Path directory, String localVersionJson, String manifestJson,
                                    ThrowingConsumer<Updater> test) throws Exception {
        FakeUpdateSource source = new FakeUpdateSource().manifest(manifestJson);
        withUpdater(directory, localVersionJson, source, test);
    }

    private static void withUpdater(Path directory, FakeUpdateSource source, ThrowingConsumer<Updater> test) throws Exception {
        withUpdater(directory, "{\"version\":\"5.3.1\",\"files\":[]}", source, test);
    }

    private static void withUpdater(Path directory, String localVersionJson, FakeUpdateSource source,
                                    ThrowingConsumer<Updater> test) throws Exception {
        Path localVersion = directory.resolve("local_version.json");
        Files.writeString(localVersion, localVersionJson);
        Updater updater = new Updater(source, directory, localVersion, directory.resolve("downloads"), System::nanoTime);
        try {
            test.accept(updater);
        } finally {
            updater.operationCoordinator().clearDownloadedFiles();
        }
    }

    private static String githubManifest(String version, String releaseNotes, boolean forceUpdate) {
        return """
                {
                  "version": "%s",
                  "releaseNotes": "%s",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v%s",
                  "forceUpdate": %s,
                  "files": []
                }
                """.formatted(version, releaseNotes, version, forceUpdate);
    }

    private static String githubManifestWithJar(String version, String jarName, String sha256, long size) {
        return githubManifestWithJar(version, jarName, jarName, sha256, size);
    }

    private static String githubManifestWithJar(String version, String id, String jarName, String sha256, long size) {
        return """
                {
                  "version": "%s",
                  "releaseNotes": "Test release notes",
                  "releasePageUrl": "https://github.com/OtterMind/Chat2DB/releases/tag/v%s",
                  "forceUpdate": false,
                  "files": [
                    {
                      "id": "%s",
                      "serverFileName": "%s",
                      "localTargetName": "%s",
                      "url": "https://github.com/OtterMind/Chat2DB/releases/download/v%s/%s",
                      "sha256": "%s",
                      "type": "jar",
                      "extractTo": null,
                      "updateStrategy": null,
                      "fileSizeByte": %d,
                      "deleted": false
                    }
                  ],
                  "launchCommand": null
                }
                """.formatted(version, version, id, jarName, jarName, version, jarName, sha256, size);
    }

    private static String sha256(byte[] bytes) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void setReleaseProfile() {
        System.setProperty("spring.profiles.active", "release");
        System.setProperty("chat2db.jcef.web-frontend", "false");
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
