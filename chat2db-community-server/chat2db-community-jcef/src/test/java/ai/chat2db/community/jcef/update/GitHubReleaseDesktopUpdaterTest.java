package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.InstallerKind;
import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.ReleaseInstaller;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseDesktopUpdaterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void checksDownloadsAndArmsTheNativeInstaller() throws Exception {
        ReleaseInstaller release = release("5.3.5");
        FakeRuntime runtime = new FakeRuntime(tempDirectory, Optional.of(release));
        List<String> statuses = new ArrayList<>();
        GitHubReleaseDesktopUpdater updater = new GitHubReleaseDesktopUpdater(
                runtime,
                (progress, status, result) -> statuses.add(progress + ":" + status),
                true
        );

        DesktopUpdateCheckResult check = updater.appCheckUpdate();
        boolean downloaded = updater.triggerDownload(new ConsoleResult());
        boolean installationAccepted = updater.triggerInstallation();
        boolean installerLaunched = updater.prepareRestart();

        assertTrue(check.needsUpdate());
        assertEquals("5.3.5", check.version());
        assertTrue(downloaded);
        assertTrue(installationAccepted);
        assertTrue(installerLaunched);
        assertEquals(release, runtime.launchedRelease);
        assertTrue(statuses.contains("50:updating"));
        assertTrue(statuses.contains("100:updated"));
    }

    @Test
    void releaseChecksAreDisabledOutsidePackagedReleaseRuntime() {
        FakeRuntime runtime = new FakeRuntime(tempDirectory, Optional.of(release("5.3.5")));
        GitHubReleaseDesktopUpdater updater = new GitHubReleaseDesktopUpdater(runtime, (progress, status, result) -> {
        }, false);

        assertFalse(updater.appCheckUpdate().needsUpdate());
        assertFalse(runtime.versionRead);
    }

    @Test
    void downloadRequiresACompletedUpdateCheck() throws Exception {
        FakeRuntime runtime = new FakeRuntime(tempDirectory, Optional.of(release("5.3.5")));
        GitHubReleaseDesktopUpdater updater = new GitHubReleaseDesktopUpdater(runtime, (progress, status, result) -> {
        }, true);

        assertFalse(updater.triggerDownload(new ConsoleResult()));
        assertFalse(updater.triggerInstallation());
    }

    @Test
    void reportsReleaseCheckFailuresSeparatelyFromNoAvailableUpdate() {
        GitHubReleaseDesktopUpdater.Runtime runtime = new GitHubReleaseDesktopUpdater.Runtime() {
            @Override
            public String currentVersion() throws Exception {
                throw new IOException("offline");
            }

            @Override
            public Optional<ReleaseInstaller> findLatest(String currentVersion) {
                return Optional.empty();
            }

            @Override
            public Path download(
                    ReleaseInstaller release,
                    GitHubReleaseDesktopUpdater.DownloadProgress progress
            ) {
                return null;
            }

            @Override
            public boolean launchInstaller(ReleaseInstaller release, Path installer, long parentPid) {
                return false;
            }
        };
        GitHubReleaseDesktopUpdater updater = new GitHubReleaseDesktopUpdater(runtime, (progress, status, result) -> {
        }, true);

        DesktopUpdateCheckResult result = updater.appCheckUpdate();

        assertFalse(result.needsUpdate());
        assertTrue(result.checkFailed());
    }

    private static ReleaseInstaller release(String version) {
        String name = "Chat2DB-Community-" + version + ".msi";
        return new ReleaseInstaller(
                version,
                URI.create("https://github.com/OtterMind/Chat2DB/releases/tag/v" + version),
                name,
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v" + version + "/" + name),
                4L,
                "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
                InstallerKind.WINDOWS_MSI
        );
    }

    private static final class FakeRuntime implements GitHubReleaseDesktopUpdater.Runtime {

        private final Path directory;
        private final Optional<ReleaseInstaller> latest;
        private boolean versionRead;
        private ReleaseInstaller launchedRelease;

        private FakeRuntime(Path directory, Optional<ReleaseInstaller> latest) {
            this.directory = directory;
            this.latest = latest;
        }

        @Override
        public String currentVersion() {
            versionRead = true;
            return "5.3.4";
        }

        @Override
        public Optional<ReleaseInstaller> findLatest(String currentVersion) {
            assertEquals("5.3.4", currentVersion);
            return latest;
        }

        @Override
        public Path download(ReleaseInstaller release, GitHubReleaseDesktopUpdater.DownloadProgress progress)
                throws Exception {
            Path installer = directory.resolve(release.assetName());
            Files.write(installer, new byte[]{0, 1, 2, 3});
            progress.update(2L, 4L);
            progress.update(4L, 4L);
            return installer;
        }

        @Override
        public boolean launchInstaller(ReleaseInstaller release, Path installer, long parentPid) {
            launchedRelease = release;
            return Files.isRegularFile(installer) && parentPid > 0L;
        }
    }
}
