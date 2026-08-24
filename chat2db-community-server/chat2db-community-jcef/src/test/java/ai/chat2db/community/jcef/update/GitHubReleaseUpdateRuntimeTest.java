package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.ReleaseInstaller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseUpdateRuntimeTest {

    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path tempDirectory;

    @Test
    void selectsOnlyArchitectureSpecificMacDmgAssets() {
        assertEquals(
                "Chat2DB-Community-5.3.5-arm64.dmg",
                GitHubReleaseUpdateRuntime.expectedAssetName("5.3.5", "arm64")
        );
        assertEquals(
                "Chat2DB-Community-5.3.5-x64.dmg",
                GitHubReleaseUpdateRuntime.expectedAssetName("5.3.5", "x64")
        );
        assertEquals("arm64", GitHubReleaseUpdateRuntime.macAssetArchitecture("aarch64"));
        assertEquals("x64", GitHubReleaseUpdateRuntime.macAssetArchitecture("x86_64"));
        assertThrows(
                IllegalStateException.class,
                () -> GitHubReleaseUpdateRuntime.macAssetArchitecture("riscv64")
        );
    }

    @Test
    void parsesOnlyTheExactStableMacReleaseAndDmg() throws Exception {
        Optional<ReleaseInstaller> release = GitHubReleaseUpdateRuntime.parseLatestRelease(
                releaseJson("5.3.5", "Chat2DB-Community-5.3.5-arm64.dmg", false, false, DIGEST),
                "5.3.4",
                "arm64",
                new ObjectMapper()
        );

        assertTrue(release.isPresent());
        assertEquals("5.3.5", release.orElseThrow().version());
        assertEquals("Chat2DB-Community-5.3.5-arm64.dmg", release.orElseThrow().assetName());
        assertEquals(DIGEST, release.orElseThrow().sha256());
    }

    @Test
    void returnsNoUpdateWhenThePublishedVersionIsNotNewer() throws Exception {
        Optional<ReleaseInstaller> release = GitHubReleaseUpdateRuntime.parseLatestRelease(
                releaseJson("5.3.4", "Chat2DB-Community-5.3.4-arm64.dmg", false, false, DIGEST),
                "5.3.4",
                "arm64",
                new ObjectMapper()
        );

        assertFalse(release.isPresent());
    }

    @Test
    void rejectsPrereleasesAndMissingDmgDigests() {
        assertThrows(
                Exception.class,
                () -> GitHubReleaseUpdateRuntime.parseLatestRelease(
                        releaseJson("5.3.5", "Chat2DB-Community-5.3.5-arm64.dmg", false, true, DIGEST),
                        "5.3.4",
                        "arm64",
                        new ObjectMapper()
                )
        );
        assertThrows(
                Exception.class,
                () -> GitHubReleaseUpdateRuntime.parseLatestRelease(
                        releaseJson("5.3.5", "Chat2DB-Community-5.3.5-arm64.dmg", false, false, ""),
                        "5.3.4",
                        "arm64",
                        new ObjectMapper()
                )
        );
    }

    @Test
    void buildsMacInstallerCommandWithVerifiedStagingBeforeActivation() throws Exception {
        Path macApp = Path.of(
                System.getProperty("user.home"),
                "Applications",
                "Chat2DB Community.app",
                "Contents",
                "app"
        );
        List<String> command = GitHubReleaseUpdateRuntime.buildInstallerCommand(
                release("Chat2DB-Community-5.3.5-arm64.dmg"),
                tempDirectory.resolve("Chat2DB-Community-5.3.5-arm64.dmg"),
                43L,
                macApp
        );
        String script = command.get(2);

        assertTrue(script.contains("\"$hdiutil_tool\" attach"));
        assertTrue(script.contains("stage=\"VERIFY_SOURCE_APP\""));
        assertTrue(script.contains("stage=\"VERIFY_STAGED_APP\""));
        assertTrue(script.contains("stage=\"ELEVATED_STAGE_COPY\""));
        assertTrue(script.contains("stage=\"ELEVATED_ACTIVATE\""));
        assertTrue(script.lastIndexOf("stage=\"VERIFY_STAGED_APP\"")
                < script.indexOf("stage=\"ELEVATED_ACTIVATE\""));
        assertTrue(script.contains("CFBundleIdentifier"));
        assertTrue(script.contains("TeamIdentifier"));
        assertTrue(script.contains("CFBundleShortVersionString"));
        assertTrue(script.contains("local_version.json"));
        assertTrue(script.contains("spctl_tool"));
        assertFalse(script.contains("recover"));
        assertFalse(script.contains("backup"));
        assertEquals("43", command.get(4));

        Path scriptFile = tempDirectory.resolve("mac-installer.sh");
        Files.writeString(scriptFile, script);
        Process syntaxCheck = new ProcessBuilder("/bin/sh", "-n", scriptFile.toString()).start();
        assertEquals(0, syntaxCheck.waitFor(), "invalid macOS installer shell syntax");
    }

    @Test
    void macScriptPersistsDmgAttachFailureAndNativeError() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"));
        Path operationDirectory = tempDirectory.resolve("mac-failure");
        Files.createDirectories(operationDirectory);
        Path missingInstaller = operationDirectory.resolve("missing.dmg");
        Path logFile = operationDirectory.resolve("update.log");
        Path resultFile = operationDirectory.resolve("latest-result.properties");
        UpdateAuditLog.NativeContext context = nativeContext(operationDirectory, logFile, resultFile);
        Path macApp = Path.of(
                System.getProperty("user.home"),
                "Applications",
                "Chat2DB Community.app",
                "Contents",
                "app"
        );
        List<String> command = GitHubReleaseUpdateRuntime.buildInstallerCommand(
                release("Chat2DB-Community-5.3.5-arm64.dmg"),
                missingInstaller,
                999_997L,
                macApp,
                context
        );

        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();

        assertTrue(process.waitFor() != 0);
        String result = Files.readString(resultFile);
        assertTrue(result.contains("status=FAILED"));
        assertTrue(result.contains("stage=ATTACH_DMG"));
        assertTrue(result.matches("(?s).*exitCode=[1-9][0-9]*.*"));
        String log = Files.readString(logFile);
        assertTrue(log.contains("stage=ATTACH_DMG"));
        assertTrue(log.contains("native installation failed"));
        assertTrue(log.contains("attaching installer="));
    }

    @Test
    void macScriptCompletesVerifiedStagingAndActivationInATemporaryApplicationsRoot() throws Exception {
        Path operationDirectory = tempDirectory.resolve("mac-success");
        Path trustedRoot = operationDirectory.resolve("Applications");
        Path destination = trustedRoot.resolve("Chat2DB Community.app");
        Path appDirectory = destination.resolve("Contents/app");
        Path toolsDirectory = operationDirectory.resolve("tools");
        Path logFile = operationDirectory.resolve("update.log");
        Path resultFile = operationDirectory.resolve("latest-result.properties");
        Path launchMarker = operationDirectory.resolve("launched");
        Files.createDirectories(trustedRoot);
        Files.createDirectories(toolsDirectory);
        Path installer = operationDirectory.resolve("installer.dmg");
        Files.writeString(installer, "fixture");
        GitHubReleaseUpdateRuntime.MacTools tools = createMacTools(
                toolsDirectory,
                launchMarker,
                "com.chat2db.community",
                "AFBZ4KGQGM",
                "5.3.5",
                "arm64"
        );
        GitHubReleaseUpdateRuntime.MacInstallPolicy policy =
                new GitHubReleaseUpdateRuntime.MacInstallPolicy(
                        List.of(trustedRoot),
                        "com.chat2db.community",
                        "AFBZ4KGQGM",
                        "arm64",
                        tools
                );
        UpdateAuditLog.NativeContext context = nativeContext(operationDirectory, logFile, resultFile);
        List<String> command = GitHubReleaseUpdateRuntime.buildMacInstallerCommand(
                installer,
                999_996L,
                appDirectory,
                context,
                policy
        );

        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();

        int exitCode = process.waitFor();
        assertEquals(0, exitCode, Files.readString(logFile));
        assertTrue(Files.isDirectory(destination));
        assertTrue(Files.isRegularFile(launchMarker));
        String result = Files.readString(resultFile);
        assertTrue(result.contains("status=SUCCESS"));
        assertTrue(result.contains("stage=COMPLETE"));
        String log = Files.readString(logFile);
        assertTrue(log.contains("stage=VERIFY_SOURCE_APP"));
        assertTrue(log.contains("stage=VERIFY_STAGED_APP"));
        assertTrue(log.contains("bundleId=com.chat2db.community"));
        assertTrue(log.contains("teamId=AFBZ4KGQGM"));
        assertTrue(log.contains("architectures=arm64"));
        assertTrue(log.contains("stage=ACTIVATE_NEW_APP"));
    }

    @Test
    void macScriptRejectsWrongBundleIdentityBeforeRemovingTheCurrentApp() throws Exception {
        Path operationDirectory = tempDirectory.resolve("mac-wrong-identity");
        Path trustedRoot = operationDirectory.resolve("Applications");
        Path destination = trustedRoot.resolve("Chat2DB Community.app");
        Path appDirectory = destination.resolve("Contents/app");
        Path existingMarker = destination.resolve("existing-version");
        Path toolsDirectory = operationDirectory.resolve("tools");
        Path logFile = operationDirectory.resolve("update.log");
        Path resultFile = operationDirectory.resolve("latest-result.properties");
        Files.createDirectories(destination);
        Files.createDirectories(toolsDirectory);
        Files.writeString(existingMarker, "keep");
        Path installer = operationDirectory.resolve("installer.dmg");
        Files.writeString(installer, "fixture");
        GitHubReleaseUpdateRuntime.MacTools tools = createMacTools(
                toolsDirectory,
                operationDirectory.resolve("launched"),
                "com.example.not-chat2db",
                "AFBZ4KGQGM",
                "5.3.5",
                "arm64"
        );
        GitHubReleaseUpdateRuntime.MacInstallPolicy policy =
                new GitHubReleaseUpdateRuntime.MacInstallPolicy(
                        List.of(trustedRoot),
                        "com.chat2db.community",
                        "AFBZ4KGQGM",
                        "arm64",
                        tools
                );
        UpdateAuditLog.NativeContext context = nativeContext(operationDirectory, logFile, resultFile);
        List<String> command = GitHubReleaseUpdateRuntime.buildMacInstallerCommand(
                installer,
                999_995L,
                appDirectory,
                context,
                policy
        );

        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();

        assertTrue(process.waitFor() != 0, Files.readString(logFile));
        assertTrue(Files.isRegularFile(existingMarker));
        String result = Files.readString(resultFile);
        assertTrue(result.contains("status=FAILED"));
        assertTrue(result.contains("stage=VERIFY_SOURCE_APP"));
        String log = Files.readString(logFile);
        assertTrue(log.contains("bundleId=com.example.not-chat2db"));
        assertFalse(log.contains("stage=REMOVE_CURRENT_APP"));
    }

    @Test
    void macElevatedPathStagesAndVerifiesBeforeActivating() throws Exception {
        Path operationDirectory = tempDirectory.resolve("mac-elevated");
        Path trustedRoot = operationDirectory.resolve("Applications");
        Path destination = trustedRoot.resolve("Chat2DB Community.app");
        Path appDirectory = destination.resolve("Contents/app");
        Path existingMarker = destination.resolve("existing-version");
        Path toolsDirectory = operationDirectory.resolve("tools");
        Path logFile = operationDirectory.resolve("update.log");
        Path resultFile = operationDirectory.resolve("latest-result.properties");
        Path launchMarker = operationDirectory.resolve("launched");
        Files.createDirectories(destination);
        Files.createDirectories(toolsDirectory);
        Files.writeString(existingMarker, "old");
        Path installer = operationDirectory.resolve("installer.dmg");
        Files.writeString(installer, "fixture");
        GitHubReleaseUpdateRuntime.MacTools tools = createMacTools(
                toolsDirectory,
                launchMarker,
                "com.chat2db.community",
                "AFBZ4KGQGM",
                "5.3.5",
                "arm64"
        );
        writeExecutable(Path.of(tools.osascript()), elevatedOsascriptShim(trustedRoot, tools.ditto()));
        GitHubReleaseUpdateRuntime.MacInstallPolicy policy =
                new GitHubReleaseUpdateRuntime.MacInstallPolicy(
                        List.of(trustedRoot),
                        "com.chat2db.community",
                        "AFBZ4KGQGM",
                        "arm64",
                        tools
                );
        UpdateAuditLog.NativeContext context = nativeContext(operationDirectory, logFile, resultFile);
        List<String> command = GitHubReleaseUpdateRuntime.buildMacInstallerCommand(
                installer,
                999_994L,
                appDirectory,
                context,
                policy
        );
        Set<PosixFilePermission> readOnlyDirectory = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
        );
        Files.setPosixFilePermissions(trustedRoot, readOnlyDirectory);

        int exitCode;
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .start();
            exitCode = process.waitFor();
        } finally {
            trustedRoot.toFile().setWritable(true, true);
        }

        assertEquals(0, exitCode, Files.readString(logFile));
        assertFalse(Files.exists(existingMarker));
        assertTrue(Files.isDirectory(destination));
        assertTrue(Files.isRegularFile(launchMarker));
        String log = Files.readString(logFile);
        int staged = log.indexOf("stage=ELEVATED_STAGE_COPY");
        int verified = log.lastIndexOf("stage=VERIFY_STAGED_APP");
        int activated = log.indexOf("stage=ELEVATED_ACTIVATE");
        assertTrue(staged >= 0);
        assertTrue(staged < verified);
        assertTrue(verified < activated);
        assertTrue(Files.readString(resultFile).contains("status=SUCCESS"));
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
                "arm64",
                (command, logFile) -> {
                },
                tempDirectory.resolve("downloads")
        );

        assertEquals("5.3.4", runtime.currentVersion());
    }

    @Test
    void onlyAllowsExpectedHttpsDownloadHostsWithoutUrlDecoration() {
        assertTrue(GitHubReleaseUpdateRuntime.isAllowedDownloadResponse(
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.3.5/file.dmg")
        ));
        assertTrue(GitHubReleaseUpdateRuntime.isAllowedDownloadResponse(
                URI.create("https://release-assets.githubusercontent.com/assets/file")
        ));
        assertFalse(GitHubReleaseUpdateRuntime.isAllowedDownloadResponse(
                URI.create("http://github.com/OtterMind/Chat2DB/releases/download/v5.3.5/file.dmg")
        ));
        assertFalse(GitHubReleaseUpdateRuntime.isAllowedDownloadResponse(
                URI.create("https://evil.example/OtterMind/Chat2DB/file.dmg")
        ));
        assertFalse(GitHubReleaseUpdateRuntime.isAllowedDownloadResponse(
                URI.create("https://github.com@evil.example/file.dmg")
        ));
        assertFalse(GitHubReleaseUpdateRuntime.isAllowedDownloadResponse(
                URI.create("https://github.com/file.dmg#fragment")
        ));
        assertEquals(
                "https://release-assets.githubusercontent.com/assets/file",
                GitHubReleaseUpdateRuntime.auditUri(
                        URI.create("https://release-assets.githubusercontent.com/assets/file?token=secret")
                )
        );
    }

    @Test
    void comparesNumericVersionsWithoutLexicalOrderingBugs() {
        assertTrue(GitHubReleaseUpdateRuntime.compareVersions("5.3.10", "5.3.4") > 0);
        assertEquals(0, GitHubReleaseUpdateRuntime.compareVersions("5.3.4", "5.3.4"));
        assertTrue(GitHubReleaseUpdateRuntime.compareVersions("5.2.99", "5.3.0") < 0);
    }

    private static ReleaseInstaller release(String assetName) {
        return new ReleaseInstaller(
                "5.3.5",
                assetName,
                URI.create("https://github.com/OtterMind/Chat2DB/releases/download/v5.3.5/" + assetName),
                1L,
                DIGEST
        );
    }

    private static UpdateAuditLog.NativeContext nativeContext(
            Path operationDirectory,
            Path logFile,
            Path resultFile
    ) {
        return new UpdateAuditLog.NativeContext(
                operationDirectory.getFileName().toString(),
                logFile,
                resultFile,
                "5.3.4",
                "5.3.5"
        );
    }

    private static GitHubReleaseUpdateRuntime.MacTools createMacTools(
            Path directory,
            Path launchMarker,
            String bundleIdentifier,
            String teamIdentifier,
            String version,
            String architecture
    ) throws Exception {
        Path hdiutil = writeExecutable(directory.resolve("hdiutil"), """
                #!/bin/sh
                if test "$1" = "attach"; then
                  mount_point=""
                  previous=""
                  for argument in "$@"; do
                    if test "$previous" = "-mountpoint"; then mount_point="$argument"; fi
                    previous="$argument"
                  done
                  app="$mount_point/Chat2DB Community.app"
                  /bin/mkdir -p "$app/Contents/MacOS" "$app/Contents/app"
                  /usr/bin/touch "$app/Contents/MacOS/Chat2DB Community"
                fi
                exit 0
                """);
        Path codesign = writeExecutable(directory.resolve("codesign"), """
                #!/bin/sh
                if test "$1" = "-dv"; then
                  echo "Identifier=%s" >&2
                  echo "TeamIdentifier=%s" >&2
                fi
                exit 0
                """.formatted(bundleIdentifier, teamIdentifier));
        Path spctl = writeExecutable(directory.resolve("spctl"), "#!/bin/sh\nexit 0\n");
        Path ditto = writeExecutable(directory.resolve("ditto"), """
                #!/bin/sh
                /bin/cp -R "$1" "$2"
                """);
        Path plistBuddy = writeExecutable(directory.resolve("PlistBuddy"), """
                #!/bin/sh
                case "$2" in
                  *CFBundleIdentifier*) echo "%s" ;;
                  *CFBundleShortVersionString*) echo "%s" ;;
                  *CFBundleVersion*) echo "%s" ;;
                  *) exit 1 ;;
                esac
                """.formatted(bundleIdentifier, version, version));
        Path plutil = writeExecutable(directory.resolve("plutil"), "#!/bin/sh\necho \"" + version + "\"\n");
        Path lipo = writeExecutable(directory.resolve("lipo"), "#!/bin/sh\necho \"" + architecture + "\"\n");
        Path osascript = writeExecutable(directory.resolve("osascript"), "#!/bin/sh\nexit 99\n");
        Path open = writeExecutable(directory.resolve("open"), """
                #!/bin/sh
                /usr/bin/touch "%s"
                """.formatted(launchMarker));
        return new GitHubReleaseUpdateRuntime.MacTools(
                hdiutil.toString(),
                codesign.toString(),
                spctl.toString(),
                ditto.toString(),
                plistBuddy.toString(),
                plutil.toString(),
                lipo.toString(),
                osascript.toString(),
                open.toString()
        );
    }

    private static Path writeExecutable(Path path, String source) throws Exception {
        Files.writeString(path, source);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private static String elevatedOsascriptShim(Path trustedRoot, String dittoPath) {
        return """
                #!/bin/sh
                third_last=""
                second_last=""
                last=""
                for argument in "$@"; do
                  third_last="$second_last"
                  second_last="$last"
                  last="$argument"
                done
                /bin/chmod 0755 "%s"
                if test "$last" = "%s"; then
                  "$last" "$third_last" "$second_last"
                  exit_code=$?
                else
                  /bin/rm -rf "$last"
                  /bin/mv "$second_last" "$last"
                  exit_code=$?
                fi
                /bin/chmod 0555 "%s"
                exit "$exit_code"
                """.formatted(trustedRoot, dittoPath, trustedRoot);
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
