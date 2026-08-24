package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.DownloadProgress;
import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.InstallerKind;
import ai.chat2db.community.jcef.update.GitHubReleaseDesktopUpdater.ReleaseInstaller;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GitHubReleaseUpdateRuntime implements GitHubReleaseDesktopUpdater.Runtime {

    @FunctionalInterface
    interface ProcessStarter {
        void start(List<String> command, Path logFile) throws IOException;
    }

    record Platform(InstallerKind kind, String assetArchitecture) {
    }

    record MacTools(
            String hdiutil,
            String codesign,
            String spctl,
            String ditto,
            String plistBuddy,
            String plutil,
            String lipo,
            String osascript,
            String open
    ) {
        static MacTools system() {
            return new MacTools(
                    "/usr/bin/hdiutil",
                    "/usr/bin/codesign",
                    "/usr/sbin/spctl",
                    "/usr/bin/ditto",
                    "/usr/libexec/PlistBuddy",
                    "/usr/bin/plutil",
                    "/usr/bin/lipo",
                    "/usr/bin/osascript",
                    "/usr/bin/open"
            );
        }
    }

    record MacInstallPolicy(
            List<Path> trustedRoots,
            String bundleIdentifier,
            String teamIdentifier,
            String architecture,
            MacTools tools
    ) {
        static MacInstallPolicy production(String architecture) {
            return new MacInstallPolicy(
                    List.of(
                            Path.of("/Applications"),
                            Path.of(System.getProperty("user.home"), "Applications")
                    ),
                    "com.chat2db.community",
                    "AFBZ4KGQGM",
                    architecture,
                    MacTools.system()
            );
        }
    }

    private record ReleasePayload(
            @JsonProperty("tag_name") String tagName,
            @JsonProperty("html_url") String htmlUrl,
            Boolean draft,
            Boolean prerelease,
            List<AssetPayload> assets
    ) {
    }

    private record AssetPayload(
            String name,
            @JsonProperty("browser_download_url") String downloadUrl,
            long size,
            String digest
    ) {
    }

    static final URI LATEST_RELEASE_API =
            URI.create("https://api.github.com/repos/OtterMind/Chat2DB/releases/latest");
    static final long MAX_INSTALLER_BYTES = 1024L * 1024L * 1024L;

    private static final long MAX_API_RESPONSE_BYTES = 2L * 1024L * 1024L;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:([a-f0-9]{64})$");
    private static final String RELEASE_PAGE_PREFIX = "https://github.com/OtterMind/Chat2DB/releases/tag/v";
    private static final String DOWNLOAD_PREFIX = "https://github.com/OtterMind/Chat2DB/releases/download/v";

    private static final String MAC_INSTALL_SCRIPT = """
            parent_pid="$1"
            installer="$2"
            destination="$3"
            result_file="$4"
            from_version="$5"
            to_version="$6"
            operation_id="$7"
            log_file="$8"
            expected_architecture="$9"
            expected_bundle_identifier="${10}"
            expected_team_identifier="${11}"
            hdiutil_tool="${12}"
            codesign_tool="${13}"
            spctl_tool="${14}"
            ditto_tool="${15}"
            plist_buddy_tool="${16}"
            plutil_tool="${17}"
            lipo_tool="${18}"
            osascript_tool="${19}"
            open_tool="${20}"
            set -e
            stage="WAIT_PARENT"
            mount_point=""
            audit() {
              /usr/bin/printf '%s level=%s stage=%s operation=%s %s\n' \
                "$(/bin/date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" "$stage" "$operation_id" "$2"
            }
            write_result() {
              result_status="$1"
              result_exit_code="$2"
              result_reason="$3"
              result_temp="${result_file}.tmp-${operation_id}"
              {
                /usr/bin/printf 'status=%s\n' "$result_status"
                /usr/bin/printf 'stage=%s\n' "$stage"
                /usr/bin/printf 'exitCode=%s\n' "$result_exit_code"
                /usr/bin/printf 'reason=%s\n' "$result_reason"
                /usr/bin/printf 'operationId=%s\n' "$operation_id"
                /usr/bin/printf 'fromVersion=%s\n' "$from_version"
                /usr/bin/printf 'toVersion=%s\n' "$to_version"
                /usr/bin/printf 'logPath=%s\n' "$log_file"
              } > "$result_temp"
              /bin/mv -f "$result_temp" "$result_file"
            }
            cleanup() {
              if test -n "$mount_point"; then
                "$hdiutil_tool" detach "$mount_point" -quiet >/dev/null 2>&1 || true
                /bin/rm -rf "$mount_point"
                mount_point=""
              fi
            }
            finish() {
              exit_code=$?
              trap - EXIT
              cleanup
              if test "$exit_code" -eq 0; then
                write_result "SUCCESS" "0" ""
              else
                audit "ERROR" "native installation failed exitCode=${exit_code}"
                write_result "FAILED" "$exit_code" "native command failed; see update.log"
              fi
              exit "$exit_code"
            }
            verify_app() {
              candidate="$1"
              requirement="anchor apple generic and certificate leaf[subject.OU] = \\"${expected_team_identifier}\\" and identifier \\"${expected_bundle_identifier}\\""
              audit "INFO" "verifying application identity path=${candidate}"
              test -d "$candidate"
              "$codesign_tool" --verify --deep --strict -R="$requirement" "$candidate"
              "$spctl_tool" --assess --type execute --verbose=4 "$candidate"
              signature_details=$("$codesign_tool" -dv --verbose=4 "$candidate" 2>&1)
              actual_team_identifier=$(/usr/bin/printf '%s\n' "$signature_details" | /usr/bin/sed -n 's/^TeamIdentifier=//p' | /usr/bin/head -n 1)
              actual_bundle_identifier=$("$plist_buddy_tool" -c 'Print :CFBundleIdentifier' "$candidate/Contents/Info.plist")
              actual_short_version=$("$plist_buddy_tool" -c 'Print :CFBundleShortVersionString' "$candidate/Contents/Info.plist")
              actual_bundle_version=$("$plist_buddy_tool" -c 'Print :CFBundleVersion' "$candidate/Contents/Info.plist")
              actual_local_version=$("$plutil_tool" -extract version raw -o - "$candidate/Contents/app/local_version.json")
              actual_architectures=$("$lipo_tool" -archs "$candidate/Contents/MacOS/Chat2DB Community")
              audit "INFO" "identity bundleId=${actual_bundle_identifier} teamId=${actual_team_identifier} shortVersion=${actual_short_version} bundleVersion=${actual_bundle_version} localVersion=${actual_local_version} architectures=${actual_architectures}"
              test "$actual_bundle_identifier" = "$expected_bundle_identifier"
              test "$actual_team_identifier" = "$expected_team_identifier"
              test "$actual_short_version" = "$to_version"
              test "$actual_bundle_version" = "$to_version"
              test "$actual_local_version" = "$to_version"
              case " $actual_architectures " in
                *" $expected_architecture "*) ;;
                *) return 1 ;;
              esac
            }
            trap finish EXIT
            trap 'exit 130' INT TERM
            audit "INFO" "waiting for application process pid=${parent_pid}"
            while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.1; done
            audit "INFO" "application process exited"
            stage="CREATE_MOUNT"
            audit "INFO" "creating temporary mount directory"
            mount_point=$(/usr/bin/mktemp -d "/tmp/chat2db-community-update.XXXXXX")
            stage="ATTACH_DMG"
            audit "INFO" "attaching installer=${installer} mountPoint=${mount_point}"
            "$hdiutil_tool" attach "$installer" -readonly -nobrowse -mountpoint "$mount_point" >/dev/null
            source_app="$mount_point/Chat2DB Community.app"
            stage="VERIFY_SOURCE_APP"
            verify_app "$source_app"
            parent_dir=$(/usr/bin/dirname "$destination")
            staged="${destination}.update-${operation_id}"
            if test -w "$parent_dir"; then
              stage="STAGE_COPY"
              audit "INFO" "copying source application to staging path=${staged}"
              /bin/rm -rf "$staged"
              "$ditto_tool" "$source_app" "$staged"
              stage="VERIFY_STAGED_APP"
              verify_app "$staged"
              stage="REMOVE_CURRENT_APP"
              audit "INFO" "removing current application path=${destination}"
              /bin/rm -rf "$destination"
              stage="ACTIVATE_NEW_APP"
              audit "INFO" "activating staged application"
              /bin/mv "$staged" "$destination"
            else
              stage="ELEVATED_STAGE_COPY"
              audit "INFO" "requesting administrator privileges to create staging application"
              "$osascript_tool" \
                -e 'on run argv' \
                -e 'set sourcePath to item 1 of argv' \
                -e 'set stagedPath to item 2 of argv' \
                -e 'set dittoPath to item 3 of argv' \
                -e 'set commandText to "/bin/rm -rf " & quoted form of stagedPath & " && " & quoted form of dittoPath & " " & quoted form of sourcePath & " " & quoted form of stagedPath' \
                -e 'do shell script commandText with administrator privileges' \
                -e 'end run' \
                "$source_app" "$staged" "$ditto_tool"
              stage="VERIFY_STAGED_APP"
              verify_app "$staged"
              stage="ELEVATED_ACTIVATE"
              audit "INFO" "requesting administrator privileges to activate staged application"
              "$osascript_tool" \
                -e 'on run argv' \
                -e 'set stagedPath to item 1 of argv' \
                -e 'set destinationPath to item 2 of argv' \
                -e 'set commandText to "/bin/rm -rf " & quoted form of destinationPath & " && /bin/mv " & quoted form of stagedPath & " " & quoted form of destinationPath' \
                -e 'do shell script commandText with administrator privileges' \
                -e 'end run' \
                "$staged" "$destination"
            fi
            stage="DETACH_DMG"
            audit "INFO" "detaching installer image"
            cleanup
            stage="REMOVE_INSTALLER"
            audit "INFO" "removing downloaded installer"
            /bin/rm -f "$installer"
            stage="LAUNCH_APPLICATION"
            audit "INFO" "launching updated application path=${destination}"
            "$open_tool" -n "$destination"
            stage="COMPLETE"
            audit "INFO" "native installation completed successfully"
            """;

    private static final String LINUX_APPIMAGE_INSTALL_SCRIPT = """
            parent_pid="$1"
            installer="$2"
            destination="$3"
            result_file="$4"
            from_version="$5"
            to_version="$6"
            operation_id="$7"
            log_file="$8"
            set -e
            stage="WAIT_PARENT"
            audit() {
              /usr/bin/printf '%s level=%s stage=%s operation=%s %s\n' \
                "$(/bin/date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" "$stage" "$operation_id" "$2"
            }
            write_result() {
              result_temp="${result_file}.tmp-${operation_id}"
              {
                /usr/bin/printf 'status=%s\n' "$1"
                /usr/bin/printf 'stage=%s\n' "$stage"
                /usr/bin/printf 'exitCode=%s\n' "$2"
                /usr/bin/printf 'reason=%s\n' "$3"
                /usr/bin/printf 'operationId=%s\n' "$operation_id"
                /usr/bin/printf 'fromVersion=%s\n' "$from_version"
                /usr/bin/printf 'toVersion=%s\n' "$to_version"
                /usr/bin/printf 'logPath=%s\n' "$log_file"
              } > "$result_temp"
              /bin/mv -f "$result_temp" "$result_file"
            }
            finish() {
              exit_code=$?
              trap - EXIT
              if test "$exit_code" -eq 0; then
                write_result "SUCCESS" "0" ""
              else
                audit "ERROR" "AppImage installation failed exitCode=${exit_code}"
                write_result "FAILED" "$exit_code" "native command failed; see update.log"
              fi
              exit "$exit_code"
            }
            trap finish EXIT
            trap 'exit 130' INT TERM
            audit "INFO" "waiting for application process pid=${parent_pid}"
            while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.1; done
            audit "INFO" "application process exited"
            staged="${destination}.update-${operation_id}"
            stage="STAGE_COPY"
            audit "INFO" "copying AppImage to staging path=${staged}"
            /bin/cp "$installer" "$staged"
            stage="MAKE_EXECUTABLE"
            audit "INFO" "marking staged AppImage executable"
            /bin/chmod 0755 "$staged"
            stage="ACTIVATE_NEW_APP"
            audit "INFO" "activating staged AppImage destination=${destination}"
            /bin/mv -f "$staged" "$destination"
            stage="REMOVE_INSTALLER"
            audit "INFO" "removing downloaded installer"
            /bin/rm -f "$installer"
            stage="LAUNCH_APPLICATION"
            audit "INFO" "launching updated AppImage"
            "$destination" >/dev/null 2>&1 &
            stage="COMPLETE"
            audit "INFO" "AppImage installation completed successfully"
            """;

    private static final String LINUX_PACKAGE_INSTALL_SCRIPT = """
            parent_pid="$1"
            installer="$2"
            package_kind="$3"
            launcher="$4"
            result_file="$5"
            from_version="$6"
            to_version="$7"
            operation_id="$8"
            log_file="$9"
            set -e
            stage="WAIT_PARENT"
            audit() {
              /usr/bin/printf '%s level=%s stage=%s operation=%s %s\n' \
                "$(/bin/date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" "$stage" "$operation_id" "$2"
            }
            write_result() {
              result_temp="${result_file}.tmp-${operation_id}"
              {
                /usr/bin/printf 'status=%s\n' "$1"
                /usr/bin/printf 'stage=%s\n' "$stage"
                /usr/bin/printf 'exitCode=%s\n' "$2"
                /usr/bin/printf 'reason=%s\n' "$3"
                /usr/bin/printf 'operationId=%s\n' "$operation_id"
                /usr/bin/printf 'fromVersion=%s\n' "$from_version"
                /usr/bin/printf 'toVersion=%s\n' "$to_version"
                /usr/bin/printf 'logPath=%s\n' "$log_file"
              } > "$result_temp"
              /bin/mv -f "$result_temp" "$result_file"
            }
            finish() {
              exit_code=$?
              trap - EXIT
              if test "$exit_code" -eq 0; then
                write_result "SUCCESS" "0" ""
              else
                audit "ERROR" "package installation failed exitCode=${exit_code} packageKind=${package_kind}"
                write_result "FAILED" "$exit_code" "native package manager failed; see update.log"
              fi
              exit "$exit_code"
            }
            trap finish EXIT
            trap 'exit 130' INT TERM
            audit "INFO" "waiting for application process pid=${parent_pid}"
            while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.1; done
            audit "INFO" "application process exited"
            if test "$package_kind" = "deb"; then
              stage="INSTALL_DEB"
              audit "INFO" "starting privileged dpkg installation installer=${installer}"
              /usr/bin/pkexec /usr/bin/dpkg -i "$installer"
            else
              stage="INSTALL_RPM"
              audit "INFO" "starting privileged rpm installation installer=${installer}"
              /usr/bin/pkexec /usr/bin/rpm -U --replacepkgs "$installer"
            fi
            stage="REMOVE_INSTALLER"
            audit "INFO" "removing downloaded installer"
            /bin/rm -f "$installer"
            stage="LAUNCH_APPLICATION"
            if test -x "$launcher"; then
              audit "INFO" "launching updated application path=${launcher}"
              "$launcher" >/dev/null 2>&1 &
            else
              audit "WARN" "updated application launcher was not found path=${launcher}"
            fi
            stage="COMPLETE"
            audit "INFO" "package installation completed successfully"
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Path appDirectory;
    private final Platform platform;
    private final Map<String, String> environment;
    private final ProcessStarter processStarter;
    private final Path downloadRoot;
    private final UpdateAuditLog auditLog;

    GitHubReleaseUpdateRuntime(UpdateAuditLog auditLog) {
        this(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(20))
                        .build(),
                new ObjectMapper(),
                Path.of(OSOperateUtil.getCurrentJarPath()).toAbsolutePath().normalize(),
                detectPlatform(
                        System.getProperty("os.name", ""),
                        System.getProperty("os.arch", ""),
                        System.getenv(),
                        Files::exists
                ),
                System.getenv(),
                (command, logFile) -> new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                        .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                        .start(),
                createDownloadRoot(),
                auditLog
        );
    }

    GitHubReleaseUpdateRuntime(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Path appDirectory,
            Platform platform,
            Map<String, String> environment,
            ProcessStarter processStarter,
            Path downloadRoot
    ) {
        this(
                httpClient,
                objectMapper,
                appDirectory,
                platform,
                environment,
                processStarter,
                downloadRoot,
                UpdateAuditLog.disabled()
        );
    }

    GitHubReleaseUpdateRuntime(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Path appDirectory,
            Platform platform,
            Map<String, String> environment,
            ProcessStarter processStarter,
            Path downloadRoot,
            UpdateAuditLog auditLog
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.appDirectory = appDirectory.toAbsolutePath().normalize();
        this.platform = platform;
        this.environment = Map.copyOf(environment);
        this.processStarter = processStarter;
        this.downloadRoot = downloadRoot.toAbsolutePath().normalize();
        this.auditLog = auditLog;
    }

    @Override
    public String currentVersion() throws IOException {
        Path versionFile = appDirectory.resolve("local_version.json");
        if (!Files.isRegularFile(versionFile)) {
            throw new IOException("Packaged Community version metadata is missing");
        }
        String version = objectMapper.readTree(versionFile.toFile()).path("version").asText("");
        if (!isStableVersion(version)) {
            throw new IOException("Packaged Community version is invalid");
        }
        return version;
    }

    @Override
    public Optional<ReleaseInstaller> findLatest(String currentVersion) throws Exception {
        auditLog.info("RELEASE_REQUEST", "GET " + LATEST_RELEASE_API);
        HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE_API)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Chat2DB-Community-Updater/" + currentVersion)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            auditLog.info(
                    "RELEASE_RESPONSE",
                    "status=" + response.statusCode() + " uri=" + response.uri()
            );
            if (response.statusCode() != 200 || !LATEST_RELEASE_API.equals(response.uri())) {
                throw new IOException("GitHub latest Release request failed: " + response.statusCode());
            }
            byte[] responseBytes = readBounded(input, MAX_API_RESPONSE_BYTES);
            auditLog.info("RELEASE_RESPONSE", "bodyBytes=" + responseBytes.length);
            return parseLatestRelease(responseBytes, currentVersion, platform, objectMapper);
        }
    }

    @Override
    public Path download(ReleaseInstaller release, DownloadProgress progress) throws Exception {
        Files.createDirectories(downloadRoot);
        Path versionDirectory = downloadRoot.resolve(release.version());
        Files.createDirectories(versionDirectory);
        Path target = versionDirectory.resolve(release.assetName());
        auditLog.info(
                "DOWNLOAD_PREPARE",
                "target=" + target + " expectedBytes=" + release.size() + " expectedSha256=" + release.sha256()
        );
        if (Files.isRegularFile(target) && verifyFile(target, release.size(), release.sha256())) {
            auditLog.info("DOWNLOAD_VERIFY", "reused existing verified installer path=" + target);
            progress.update(release.size(), release.size());
            return target;
        }

        Path partial = versionDirectory.resolve(release.assetName() + ".part");
        Files.deleteIfExists(partial);
        HttpRequest request = HttpRequest.newBuilder(release.downloadUri())
                .timeout(Duration.ofMinutes(30))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Chat2DB-Community-Updater/" + release.version())
                .GET()
                .build();
        auditLog.info("DOWNLOAD_REQUEST", "GET " + release.downloadUri());
        HttpResponse<InputStream> response = sendInstallerRequest(request);
        try (InputStream input = response.body()) {
            auditLog.info(
                    "DOWNLOAD_RESPONSE",
                    "status=" + response.statusCode()
                            + " finalUri=" + auditUri(response.uri())
                            + " contentLength=" + response.headers().firstValue("Content-Length").orElse("")
            );
            if (response.statusCode() != 200 || !isAllowedDownloadResponse(response.uri())) {
                throw new IOException("GitHub Release installer download failed: " + response.statusCode());
            }
            Optional<String> contentLength = response.headers().firstValue("Content-Length");
            if (contentLength.isPresent() && Long.parseLong(contentLength.get()) != release.size()) {
                throw new IOException("GitHub Release installer size header does not match");
            }
            streamInstaller(input, partial, release, progress);
            auditLog.info(
                    "DOWNLOAD_VERIFY",
                    "verification passed path=" + partial
                            + " bytes=" + release.size()
                            + " sha256=" + release.sha256()
            );
            moveAtomically(partial, target);
            auditLog.info("DOWNLOAD_FINALIZE", "installer finalized path=" + target);
            return target;
        } catch (Exception exception) {
            Files.deleteIfExists(partial);
            throw exception;
        }
    }

    @Override
    public boolean launchInstaller(ReleaseInstaller release, Path installer, long parentPid) throws Exception {
        auditLog.info(
                "INSTALL_VERIFY",
                "revalidating installer path=" + installer
                        + " expectedBytes=" + release.size()
                        + " expectedSha256=" + release.sha256()
        );
        if (!Files.isRegularFile(installer) || !verifyFile(installer, release.size(), release.sha256())) {
            throw new IOException("Downloaded Community installer failed verification");
        }
        auditLog.info("INSTALL_VERIFY", "installer revalidation passed");
        validateInstallerEnvironment(release);
        auditLog.info("INSTALL_ENVIRONMENT", "platform installer requirements passed kind=" + release.kind());
        UpdateAuditLog.NativeContext nativeContext = auditLog.prepareNativeHandoff();
        if (nativeContext == null) {
            nativeContext = testNativeContext(installer);
        }
        List<String> command = buildInstallerCommand(
                release,
                installer.toAbsolutePath().normalize(),
                parentPid,
                appDirectory,
                environment,
                nativeContext
        );
        auditLog.info(
                "INSTALL_PROCESS",
                "starting native installer kind=" + release.kind() + " parentPid=" + parentPid
        );
        processStarter.start(command, nativeContext.logFile());
        auditLog.info("INSTALL_PROCESS", "native installer process started");
        return true;
    }

    private void validateInstallerEnvironment(ReleaseInstaller release) throws IOException {
        switch (release.kind()) {
            case WINDOWS_MSI, MAC_DMG -> {
                return;
            }
            case LINUX_APPIMAGE -> {
                String appImage = environment.get("APPIMAGE");
                if (appImage == null || appImage.isBlank()) {
                    throw new IOException("APPIMAGE is missing; cannot replace the running AppImage");
                }
                Path destination = Path.of(appImage).toAbsolutePath().normalize();
                if (!Files.isRegularFile(destination)
                        || destination.getParent() == null
                        || !Files.isWritable(destination.getParent())) {
                    throw new IOException("The running AppImage cannot be replaced");
                }
            }
            case LINUX_DEB -> requireExecutables(Path.of("/usr/bin/pkexec"), Path.of("/usr/bin/dpkg"));
            case LINUX_RPM -> requireExecutables(Path.of("/usr/bin/pkexec"), Path.of("/usr/bin/rpm"));
        }
    }

    private static void requireExecutables(Path... executables) throws IOException {
        for (Path executable : executables) {
            if (!Files.isExecutable(executable)) {
                throw new IOException("Required installer command is unavailable: " + executable);
            }
        }
    }

    private static Path createDownloadRoot() {
        try {
            return Files.createTempDirectory("chat2db-community-updater-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the Community updater directory", exception);
        }
    }

    static Optional<ReleaseInstaller> parseLatestRelease(
            byte[] json,
            String currentVersion,
            Platform platform,
            ObjectMapper objectMapper
    ) throws IOException {
        if (!isStableVersion(currentVersion)) {
            throw new IOException("Current version is invalid");
        }
        ReleasePayload payload = objectMapper.readValue(json, ReleasePayload.class);
        if (payload == null
                || !Boolean.FALSE.equals(payload.draft())
                || !Boolean.FALSE.equals(payload.prerelease())) {
            throw new IOException("GitHub latest Release is not a stable published Release");
        }
        Matcher tagMatch = VERSION_PATTERN.matcher(payload.tagName() == null ? "" : payload.tagName());
        if (!tagMatch.matches() || !payload.tagName().startsWith("v")) {
            throw new IOException("GitHub Release tag must use vX.Y.Z");
        }
        String version = tagMatch.group(1) + "." + tagMatch.group(2) + "." + tagMatch.group(3);
        String expectedPage = RELEASE_PAGE_PREFIX + version;
        if (!expectedPage.equals(payload.htmlUrl())) {
            throw new IOException("GitHub Release page URL is invalid");
        }
        if (compareVersions(version, currentVersion) <= 0) {
            return Optional.empty();
        }

        String expectedName = expectedAssetName(version, platform);
        List<AssetPayload> matches = payload.assets() == null
                ? List.of()
                : payload.assets().stream().filter(asset -> expectedName.equals(asset.name())).toList();
        if (matches.size() != 1) {
            throw new IOException("GitHub Release does not contain exactly one installer for this platform");
        }
        AssetPayload asset = matches.get(0);
        String expectedUrl = DOWNLOAD_PREFIX + version + "/" + expectedName;
        if (!expectedUrl.equals(asset.downloadUrl())) {
            throw new IOException("GitHub Release installer URL is invalid");
        }
        if (asset.size() <= 0 || asset.size() > MAX_INSTALLER_BYTES) {
            throw new IOException("GitHub Release installer size is invalid");
        }
        Matcher digestMatch = SHA256_PATTERN.matcher(asset.digest() == null ? "" : asset.digest());
        if (!digestMatch.matches()) {
            throw new IOException("GitHub Release installer SHA-256 digest is missing");
        }
        return Optional.of(new ReleaseInstaller(
                version,
                URI.create(expectedPage),
                expectedName,
                URI.create(expectedUrl),
                asset.size(),
                digestMatch.group(1),
                platform.kind()
        ));
    }

    static Platform detectPlatform(
            String osName,
            String architecture,
            Map<String, String> environment,
            Predicate<Path> exists
    ) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = normalizeArchitecture(architecture);
        if (os.contains("win")) {
            return new Platform(InstallerKind.WINDOWS_MSI, "");
        }
        if (os.contains("mac")) {
            return new Platform(InstallerKind.MAC_DMG, "arm64".equals(arch) ? "arm64" : "x64");
        }
        if (!os.contains("linux")) {
            throw new IllegalStateException("Unsupported desktop update platform: " + osName);
        }
        String appImage = environment.get("APPIMAGE");
        if (appImage != null && !appImage.isBlank()) {
            return new Platform(InstallerKind.LINUX_APPIMAGE, "arm64".equals(arch) ? "arm64" : "x86_64");
        }
        if (exists.test(Path.of("/etc/debian_version"))) {
            return new Platform(InstallerKind.LINUX_DEB, "arm64".equals(arch) ? "arm64" : "amd64");
        }
        if (exists.test(Path.of("/etc/redhat-release"))
                || exists.test(Path.of("/etc/fedora-release"))
                || exists.test(Path.of("/etc/SuSE-release"))) {
            return new Platform(InstallerKind.LINUX_RPM, "arm64".equals(arch) ? "aarch64" : "x86_64");
        }
        throw new IllegalStateException("Unsupported Linux package format for automatic updates");
    }

    static String expectedAssetName(String version, Platform platform) {
        return switch (platform.kind()) {
            case WINDOWS_MSI -> "Chat2DB-Community-" + version + ".msi";
            case MAC_DMG -> "Chat2DB-Community-" + version + "-" + platform.assetArchitecture() + ".dmg";
            case LINUX_DEB -> "Chat2DB-Community-" + version + "-" + platform.assetArchitecture() + ".deb";
            case LINUX_RPM -> "Chat2DB-Community-" + version + "-" + platform.assetArchitecture() + ".rpm";
            case LINUX_APPIMAGE ->
                    "Chat2DB-Community-" + version + "-" + platform.assetArchitecture() + ".AppImage";
        };
    }

    static List<String> buildInstallerCommand(
            ReleaseInstaller release,
            Path installer,
            long parentPid,
            Path appDirectory,
            Map<String, String> environment
    ) throws IOException {
        return buildInstallerCommand(
                release,
                installer,
                parentPid,
                appDirectory,
                environment,
                testNativeContext(installer)
        );
    }

    static List<String> buildInstallerCommand(
            ReleaseInstaller release,
            Path installer,
            long parentPid,
            Path appDirectory,
            Map<String, String> environment,
            UpdateAuditLog.NativeContext nativeContext
    ) throws IOException {
        return switch (release.kind()) {
            case WINDOWS_MSI -> buildWindowsInstallerCommand(installer, parentPid, appDirectory, nativeContext);
            case MAC_DMG -> buildMacInstallerCommand(
                    installer,
                    parentPid,
                    appDirectory,
                    nativeContext,
                    MacInstallPolicy.production(expectedMacArchitecture(release.assetName()))
            );
            case LINUX_APPIMAGE -> buildAppImageInstallerCommand(installer, parentPid, environment, nativeContext);
            case LINUX_DEB ->
                    buildLinuxPackageInstallerCommand(installer, parentPid, appDirectory, "deb", nativeContext);
            case LINUX_RPM ->
                    buildLinuxPackageInstallerCommand(installer, parentPid, appDirectory, "rpm", nativeContext);
        };
    }

    static int compareVersions(String left, String right) {
        Matcher leftMatcher = VERSION_PATTERN.matcher(left);
        Matcher rightMatcher = VERSION_PATTERN.matcher(right);
        if (!leftMatcher.matches() || !rightMatcher.matches()) {
            throw new IllegalArgumentException("Versions must use X.Y.Z");
        }
        for (int index = 1; index <= 3; index++) {
            int comparison = new BigInteger(leftMatcher.group(index)).compareTo(new BigInteger(rightMatcher.group(index)));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static List<String> buildWindowsInstallerCommand(
            Path installer,
            long parentPid,
            Path appDirectory,
            UpdateAuditLog.NativeContext nativeContext
    ) {
        Path launcher = findAncestorChild(appDirectory, "Chat2DB Community.exe");
        String arguments = "/i \"" + installer + "\" /L*v \"" + nativeContext.nativeInstallerLog()
                + "\" /passive /norestart";
        StringBuilder script = new StringBuilder("$ErrorActionPreference='Stop';")
                .append("$updateLog=").append(powerShellLiteral(nativeContext.logFile().toString())).append(";")
                .append("$resultFile=").append(powerShellLiteral(nativeContext.resultFile().toString())).append(";")
                .append("$operationId=").append(powerShellLiteral(nativeContext.operationId())).append(";")
                .append("$fromVersion=").append(powerShellLiteral(nativeContext.fromVersion())).append(";")
                .append("$toVersion=").append(powerShellLiteral(nativeContext.toVersion())).append(";")
                .append("$stage='WAIT_PARENT';")
                .append("function Write-Audit{param([string]$level,[string]$message);")
                .append("$timestamp=[DateTime]::UtcNow.ToString('o');")
                .append("Add-Content -LiteralPath $updateLog -Encoding UTF8 -Value ")
                .append("\"$timestamp level=$level stage=$stage operation=$operationId $message\"};")
                .append("function Write-Result{param([string]$status,[int]$exitCode,[string]$reason);")
                .append("$safeReason=$reason.Replace([char]13,' ').Replace([char]10,' ');")
                .append("$temp=\"$resultFile.tmp-$operationId\";")
                .append("@(\"status=$status\",\"stage=$stage\",\"exitCode=$exitCode\",")
                .append("\"reason=$safeReason\",\"operationId=$operationId\",")
                .append("\"fromVersion=$fromVersion\",\"toVersion=$toVersion\",")
                .append("\"logPath=$updateLog\") | Set-Content -LiteralPath $temp -Encoding UTF8;")
                .append("Move-Item -LiteralPath $temp -Destination $resultFile -Force};")
                .append("Write-Audit 'INFO' 'waiting for application process pid=").append(parentPid).append("';")
                .append("Wait-Process -Id ").append(parentPid).append(" -ErrorAction SilentlyContinue;")
                .append("Write-Audit 'INFO' 'application process exited';")
                .append("try{$msiexec=Join-Path $env:WINDIR 'System32\\msiexec.exe';")
                .append("if(!(Test-Path -LiteralPath $msiexec)){throw ")
                .append(powerShellLiteral("System MSI installer is unavailable")).append("};")
                .append("$stage='INSTALL_MSI';")
                .append("Write-Audit 'INFO' 'starting elevated MSI installer with verbose native log';")
                .append("$process=Start-Process -FilePath $msiexec -ArgumentList ")
                .append(powerShellLiteral(arguments))
                .append(" -Verb RunAs -Wait -PassThru;")
                .append("Write-Audit 'INFO' (\"MSI installer exited code=\"+$process.ExitCode);")
                .append("if(@(0,1641,3010) -notcontains $process.ExitCode){throw ")
                .append(powerShellLiteral("MSI installation failed")).append("};")
                .append("$stage='REMOVE_INSTALLER';")
                .append("Write-Audit 'INFO' 'removing downloaded installer';")
                .append("Remove-Item -LiteralPath ").append(powerShellLiteral(installer.toString()))
                .append(" -Force -ErrorAction SilentlyContinue;");
        if (launcher != null) {
            script.append("$stage='LAUNCH_APPLICATION';")
                    .append("Write-Audit 'INFO' 'launching updated application';")
                    .append("if(Test-Path -LiteralPath ").append(powerShellLiteral(launcher.toString()))
                    .append("){Start-Process -FilePath ").append(powerShellLiteral(launcher.toString())).append("};");
        } else {
            script.append("Write-Audit 'WARN' 'updated application launcher was not found';");
        }
        script.append("$stage='COMPLETE';")
                .append("Write-Audit 'INFO' 'MSI installation completed successfully';")
                .append("Write-Result 'SUCCESS' 0 '';exit 0}")
                .append("catch{$reason=$_.Exception.ToString();")
                .append("Write-Audit 'ERROR' $reason;Write-Result 'FAILED' 1 $reason;exit 1};");
        String encoded = Base64.getEncoder().encodeToString(
                script.toString().getBytes(StandardCharsets.UTF_16LE)
        );
        return List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-EncodedCommand",
                encoded
        );
    }

    static List<String> buildMacInstallerCommand(
            Path installer,
            long parentPid,
            Path appDirectory,
            UpdateAuditLog.NativeContext nativeContext,
            MacInstallPolicy policy
    )
            throws IOException {
        Path bundle = findMacApplicationBundle(appDirectory);
        if (bundle == null || !isTrustedMacDestination(bundle, policy.trustedRoots())) {
            throw new IOException("Chat2DB Community must be installed in Applications before automatic updates");
        }
        MacTools tools = policy.tools();
        return List.of(
                "/bin/sh",
                "-c",
                MAC_INSTALL_SCRIPT,
                "chat2db-community-update",
                Long.toString(parentPid),
                installer.toString(),
                bundle.toString(),
                nativeContext.resultFile().toString(),
                nativeContext.fromVersion(),
                nativeContext.toVersion(),
                nativeContext.operationId(),
                nativeContext.logFile().toString(),
                policy.architecture(),
                policy.bundleIdentifier(),
                policy.teamIdentifier(),
                tools.hdiutil(),
                tools.codesign(),
                tools.spctl(),
                tools.ditto(),
                tools.plistBuddy(),
                tools.plutil(),
                tools.lipo(),
                tools.osascript(),
                tools.open()
        );
    }

    private static List<String> buildAppImageInstallerCommand(
            Path installer,
            long parentPid,
            Map<String, String> environment,
            UpdateAuditLog.NativeContext nativeContext
    ) throws IOException {
        String appImage = environment.get("APPIMAGE");
        if (appImage == null || appImage.isBlank()) {
            throw new IOException("APPIMAGE is missing; cannot replace the running AppImage");
        }
        Path destination = Path.of(appImage).toAbsolutePath().normalize();
        return List.of(
                "/bin/sh",
                "-c",
                LINUX_APPIMAGE_INSTALL_SCRIPT,
                "chat2db-community-update",
                Long.toString(parentPid),
                installer.toString(),
                destination.toString(),
                nativeContext.resultFile().toString(),
                nativeContext.fromVersion(),
                nativeContext.toVersion(),
                nativeContext.operationId(),
                nativeContext.logFile().toString()
        );
    }

    private static List<String> buildLinuxPackageInstallerCommand(
            Path installer,
            long parentPid,
            Path appDirectory,
            String packageKind,
            UpdateAuditLog.NativeContext nativeContext
    ) {
        Path launcher = findLinuxLauncher(appDirectory);
        return List.of(
                "/bin/sh",
                "-c",
                LINUX_PACKAGE_INSTALL_SCRIPT,
                "chat2db-community-update",
                Long.toString(parentPid),
                installer.toString(),
                packageKind,
                launcher == null ? "" : launcher.toString(),
                nativeContext.resultFile().toString(),
                nativeContext.fromVersion(),
                nativeContext.toVersion(),
                nativeContext.operationId(),
                nativeContext.logFile().toString()
        );
    }

    private static String normalizeArchitecture(String architecture) {
        String arch = architecture.toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x86-64")) {
            return "x64";
        }
        throw new IllegalStateException("Unsupported desktop update architecture: " + architecture);
    }

    private static boolean isStableVersion(String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION_PATTERN.matcher(version);
        return matcher.matches() && !version.startsWith("v");
    }

    private static void streamInstaller(
            InputStream input,
            Path destination,
            ReleaseInstaller release,
            DownloadProgress progress
    ) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = Files.newOutputStream(
                destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > release.size() || total > MAX_INSTALLER_BYTES) {
                    throw new IOException("GitHub Release installer exceeds the expected size");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                progress.update(total, release.size());
            }
        }
        String actualDigest = HexFormat.of().formatHex(digest.digest());
        if (total != release.size() || !actualDigest.equals(release.sha256())) {
            throw new IOException(
                    "GitHub Release installer verification failed"
                            + ": expectedBytes=" + release.size()
                            + ", actualBytes=" + total
                            + ", expectedSha256=" + release.sha256()
                            + ", actualSha256=" + actualDigest
            );
        }
    }

    private boolean verifyFile(Path file, long expectedSize, String expectedSha256) throws Exception {
        long actualSize = Files.size(file);
        if (actualSize != expectedSize) {
            auditLog.warn(
                    "VERIFY_FILE",
                    "size mismatch path=" + file + " expectedBytes=" + expectedSize + " actualBytes=" + actualSize
            );
            return false;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        if (!actualSha256.equals(expectedSha256)) {
            auditLog.warn(
                    "VERIFY_FILE",
                    "SHA-256 mismatch path=" + file
                            + " expectedSha256=" + expectedSha256
                            + " actualSha256=" + actualSha256
            );
            return false;
        }
        auditLog.info(
                "VERIFY_FILE",
                "verification passed path=" + file
                        + " bytes=" + actualSize
                        + " sha256=" + actualSha256
        );
        return true;
    }

    private static byte[] readBounded(InputStream input, long maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("GitHub API response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean isAllowedDownloadResponse(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            return false;
        }
        String host = uri.getHost();
        return "github.com".equalsIgnoreCase(host)
                || "release-assets.githubusercontent.com".equalsIgnoreCase(host)
                || "objects.githubusercontent.com".equalsIgnoreCase(host);
    }

    private HttpResponse<InputStream> sendInstallerRequest(HttpRequest initialRequest) throws Exception {
        URI currentUri = initialRequest.uri();
        if (!isAllowedDownloadResponse(currentUri)) {
            throw new IOException("GitHub Release installer URL is not allowed");
        }
        for (int redirect = 0; redirect <= 5; redirect++) {
            HttpRequest request = initialRequest.newBuilder(currentUri).build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            String location = response.headers().firstValue("Location").orElse("");
            try (InputStream ignored = response.body()) {
                if (location.isBlank()) {
                    throw new IOException("GitHub Release installer redirect has no Location");
                }
            }
            URI nextUri;
            try {
                nextUri = currentUri.resolve(location);
            } catch (IllegalArgumentException exception) {
                throw new IOException("GitHub Release installer redirect is invalid", exception);
            }
            if (!isAllowedDownloadResponse(nextUri)) {
                throw new IOException("GitHub Release installer redirect host is not allowed");
            }
            auditLog.info(
                    "DOWNLOAD_REDIRECT",
                    "hop=" + (redirect + 1)
                            + " status=" + response.statusCode()
                            + " from=" + auditUri(currentUri)
                            + " to=" + auditUri(nextUri)
            );
            currentUri = nextUri;
        }
        throw new IOException("GitHub Release installer redirect limit exceeded");
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    static String auditUri(URI uri) {
        if (uri == null) {
            return "";
        }
        String authority = uri.getHost() == null ? "" : uri.getHost();
        if (uri.getPort() >= 0) {
            authority += ":" + uri.getPort();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        return uri.getScheme() + "://" + authority + path;
    }

    private static UpdateAuditLog.NativeContext testNativeContext(Path installer) {
        Path directory = installer.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        }
        return new UpdateAuditLog.NativeContext(
                "test-operation",
                directory.resolve("update.log"),
                directory.resolve("native-installer.log"),
                directory.resolve("latest-result.properties"),
                "",
                ""
        );
    }

    private static Path findAncestorChild(Path start, String childName) {
        Path current = start.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 6; depth++, current = current.getParent()) {
            Path candidate = current.resolve(childName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path findMacApplicationBundle(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path fileName = current.getFileName();
            if (fileName != null && "Chat2DB Community.app".equals(fileName.toString())) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isTrustedMacDestination(Path bundle, List<Path> trustedRoots) {
        Path normalized = bundle.toAbsolutePath().normalize();
        return trustedRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(normalized::startsWith);
    }

    private static String expectedMacArchitecture(String assetName) throws IOException {
        if (assetName != null && assetName.endsWith("-arm64.dmg")) {
            return "arm64";
        }
        if (assetName != null && assetName.endsWith("-x64.dmg")) {
            return "x86_64";
        }
        throw new IOException("Could not resolve the macOS installer architecture from asset " + assetName);
    }

    private static Path findLinuxLauncher(Path appDirectory) {
        Path current = appDirectory.toAbsolutePath().normalize();
        while (current != null) {
            Path fileName = current.getFileName();
            if (fileName != null && "chat2db-community".equals(fileName.toString())) {
                return current.resolve("bin").resolve("Chat2DB Community");
            }
            current = current.getParent();
        }
        return Path.of("/opt/chat2db-community/bin/Chat2DB Community");
    }

    private static String powerShellLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
