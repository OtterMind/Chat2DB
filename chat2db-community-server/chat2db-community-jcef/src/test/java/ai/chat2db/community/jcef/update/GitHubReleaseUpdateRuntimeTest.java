package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.InstallerKind;
import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.ReleaseInstaller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseUpdateRuntimeTest {

    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path tempDirectory;

    @Test
    void selectsExistingReleaseAssetsForEveryPackagedPlatform() {
        assertEquals(
                "Chat2DB-Community-5.3.5.msi",
                GitHubReleaseUpdateRuntime.expectedAssetName(
                        "5.3.5",
                        new GitHubReleaseUpdateRuntime.Platform(InstallerKind.WINDOWS_MSI, "")
                )
        );
        assertEquals(
                "Chat2DB-Community-5.3.5-arm64.dmg",
                GitHubReleaseUpdateRuntime.expectedAssetName(
                        "5.3.5",
                        new GitHubReleaseUpdateRuntime.Platform(InstallerKind.MAC_DMG, "arm64")
                )
        );
        assertEquals(
                "Chat2DB-Community-5.3.5-amd64.deb",
                GitHubReleaseUpdateRuntime.expectedAssetName(
                        "5.3.5",
                        new GitHubReleaseUpdateRuntime.Platform(InstallerKind.LINUX_DEB, "amd64")
                )
        );
        assertEquals(
                "Chat2DB-Community-5.3.5-aarch64.rpm",
                GitHubReleaseUpdateRuntime.expectedAssetName(
                        "5.3.5",
                        new GitHubReleaseUpdateRuntime.Platform(InstallerKind.LINUX_RPM, "aarch64")
                )
        );
        assertEquals(
                "Chat2DB-Community-5.3.5-x86_64.AppImage",
                GitHubReleaseUpdateRuntime.expectedAssetName(
                        "5.3.5",
                        new GitHubReleaseUpdateRuntime.Platform(InstallerKind.LINUX_APPIMAGE, "x86_64")
                )
        );
    }

    @Test
    void parsesOnlyTheExactStableReleaseAndInstaller() throws Exception {
        GitHubReleaseUpdateRuntime.Platform platform =
                new GitHubReleaseUpdateRuntime.Platform(InstallerKind.WINDOWS_MSI, "");
        Optional<ReleaseInstaller> release = GitHubReleaseUpdateRuntime.parseLatestRelease(
                releaseJson("5.3.5", "Chat2DB-Community-5.3.5.msi", false, false, DIGEST),
                "5.3.4",
                platform,
                new ObjectMapper()
        );

        assertTrue(release.isPresent());
        assertEquals("5.3.5", release.orElseThrow().version());
        assertEquals(DIGEST, release.orElseThrow().sha256());
        assertEquals(InstallerKind.WINDOWS_MSI, release.orElseThrow().kind());
    }

    @Test
    void returnsNoUpdateWhenThePublishedVersionIsNotNewer() throws Exception {
        GitHubReleaseUpdateRuntime.Platform platform =
                new GitHubReleaseUpdateRuntime.Platform(InstallerKind.WINDOWS_MSI, "");

        Optional<ReleaseInstaller> release = GitHubReleaseUpdateRuntime.parseLatestRelease(
                releaseJson("5.3.4", "Chat2DB-Community-5.3.4.msi", false, false, DIGEST),
                "5.3.4",
                platform,
                new ObjectMapper()
        );

        assertFalse(release.isPresent());
    }

    @Test
    void rejectsPrereleasesAndMissingDigests() {
        GitHubReleaseUpdateRuntime.Platform platform =
                new GitHubReleaseUpdateRuntime.Platform(InstallerKind.WINDOWS_MSI, "");

        assertThrows(
                Exception.class,
                () -> GitHubReleaseUpdateRuntime.parseLatestRelease(
                        releaseJson("5.3.5", "Chat2DB-Community-5.3.5.msi", false, true, DIGEST),
                        "5.3.4",
                        platform,
                        new ObjectMapper()
                )
        );
        assertThrows(
                Exception.class,
                () -> GitHubReleaseUpdateRuntime.parseLatestRelease(
                        releaseJson("5.3.5", "Chat2DB-Community-5.3.5.msi", false, false, ""),
                        "5.3.4",
                        platform,
                        new ObjectMapper()
                )
        );
    }

    @Test
    void detectsLinuxPackageFormatsAndArchitectures() {
        assertEquals(
                InstallerKind.LINUX_APPIMAGE,
                GitHubReleaseUpdateRuntime.detectPlatform(
                        "Linux",
                        "amd64",
                        Map.of("APPIMAGE", "/tmp/Chat2DB.AppImage"),
                        path -> false
                ).kind()
        );
        assertEquals(
                "arm64",
                GitHubReleaseUpdateRuntime.detectPlatform(
                        "Linux",
                        "aarch64",
                        Map.of(),
                        path -> path.equals(Path.of("/etc/debian_version"))
                ).assetArchitecture()
        );
        assertEquals(
                InstallerKind.LINUX_RPM,
                GitHubReleaseUpdateRuntime.detectPlatform(
                        "Linux",
                        "x86_64",
                        Map.of(),
                        path -> path.equals(Path.of("/etc/redhat-release"))
                ).kind()
        );
    }

    @Test
    void buildsInstallCommandsThatWaitForTheRunningApplication() throws Exception {
        Path windowsRoot = tempDirectory.resolve("windows");
        Path windowsApp = windowsRoot.resolve("app");
        Files.createDirectories(windowsApp);
        Files.createFile(windowsRoot.resolve("Chat2DB Community.exe"));
        ReleaseInstaller windowsRelease = release(InstallerKind.WINDOWS_MSI, "Chat2DB-Community-5.3.5.msi");
        List<String> windows = GitHubReleaseUpdateRuntime.buildInstallerCommand(
                windowsRelease,
                tempDirectory.resolve("Chat2DB-Community-5.3.5.msi"),
                42L,
                windowsApp,
                Map.of()
        );
        String powerShell = new String(
                Base64.getDecoder().decode(windows.get(windows.size() - 1)),
                StandardCharsets.UTF_16LE
        );
        assertTrue(powerShell.contains("Wait-Process -Id 42"));
        assertTrue(powerShell.contains("msiexec.exe"));
        assertTrue(powerShell.contains("/passive /norestart"));
        assertTrue(powerShell.contains("catch{exit 1}"));

        Path macApp = Path.of(System.getProperty("user.home"), "Applications", "Chat2DB Community.app", "Contents", "app");
        ReleaseInstaller macRelease = release(InstallerKind.MAC_DMG, "Chat2DB-Community-5.3.5-arm64.dmg");
        List<String> mac = GitHubReleaseUpdateRuntime.buildInstallerCommand(
                macRelease,
                tempDirectory.resolve("Chat2DB-Community-5.3.5-arm64.dmg"),
                43L,
                macApp,
                Map.of()
        );
        assertTrue(mac.get(2).contains("hdiutil attach"));
        assertTrue(mac.get(2).contains("administrator privileges"));
        assertFalse(mac.get(2).contains("recover"));
        assertFalse(mac.get(2).contains("backup"));
        assertEquals("43", mac.get(4));

        ReleaseInstaller appImageRelease =
                release(InstallerKind.LINUX_APPIMAGE, "Chat2DB-Community-5.3.5-x86_64.AppImage");
        List<String> appImage = GitHubReleaseUpdateRuntime.buildInstallerCommand(
                appImageRelease,
                tempDirectory.resolve("new.AppImage"),
                44L,
                tempDirectory,
                Map.of("APPIMAGE", tempDirectory.resolve("current.AppImage").toString())
        );
        assertTrue(appImage.get(2).contains("chmod 0755"));
        assertFalse(appImage.get(2).contains("recover"));
        assertEquals("44", appImage.get(4));
    }

    @Test
    void readsThePackagedVersionMetadata() throws Exception {
        Path appDirectory = tempDirectory.resolve("app");
        Files.createDirectories(appDirectory);
        Files.writeString(appDirectory.resolve("local_version.json"), "{\"version\":\"5.3.4\"}");
        GitHubReleaseUpdateRuntime runtime = new GitHubReleaseUpdateRuntime(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                appDirectory,
                new GitHubReleaseUpdateRuntime.Platform(InstallerKind.WINDOWS_MSI, ""),
                Map.of(),
                command -> {
                },
                tempDirectory.resolve("downloads")
        );

        assertEquals("5.3.4", runtime.currentVersion());
    }

    @Test
    void comparesNumericVersionsWithoutLexicalOrderingBugs() {
        assertTrue(GitHubReleaseUpdateRuntime.compareVersions("5.3.10", "5.3.4") > 0);
        assertEquals(0, GitHubReleaseUpdateRuntime.compareVersions("5.3.4", "5.3.4"));
        assertTrue(GitHubReleaseUpdateRuntime.compareVersions("5.2.99", "5.3.0") < 0);
    }

    private static ReleaseInstaller release(InstallerKind kind, String assetName) {
        return new ReleaseInstaller(
                "5.3.5",
                URI.create("https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.5"),
                assetName,
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.3.5/" + assetName),
                1L,
                DIGEST,
                kind
        );
    }

    private static byte[] releaseJson(
            String version,
            String assetName,
            boolean draft,
            boolean prerelease,
            String digest
    ) {
        return ("""
                {
                  "tag_name": "v%s",
                  "html_url": "https://github.com/OtterMind/Chat2DB/releases/tag/v%s",
                  "draft": %s,
                  "prerelease": %s,
                  "assets": [
                    {
                      "name": "%s",
                      "browser_download_url": "https://github.com/OtterMind/Chat2DB/releases/download/v%s/%s",
                      "size": 123,
                      "digest": "sha256:%s"
                    }
                  ]
                }
                """).formatted(version, version, draft, prerelease, assetName, version, assetName, digest)
                .getBytes(StandardCharsets.UTF_8);
    }
}
