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
        void start(List<String> command) throws IOException;
    }

    record Platform(InstallerKind kind, String assetArchitecture) {
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
            set -e
            while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.1; done
            mount_point=$(/usr/bin/mktemp -d "/tmp/chat2db-community-update.XXXXXX")
            cleanup() {
              /usr/bin/hdiutil detach "$mount_point" -quiet >/dev/null 2>&1 || true
              /bin/rm -rf "$mount_point"
            }
            trap cleanup EXIT INT TERM
            /usr/bin/hdiutil attach "$installer" -readonly -nobrowse -mountpoint "$mount_point" >/dev/null
            source_app="$mount_point/Chat2DB Community.app"
            test -d "$source_app"
            /usr/bin/codesign --verify --deep --strict "$source_app"
            parent_dir=$(/usr/bin/dirname "$destination")
            staged="${destination}.update-${parent_pid}"
            if test -w "$parent_dir"; then
              /bin/rm -rf "$staged"
              /usr/bin/ditto "$source_app" "$staged"
              /bin/rm -rf "$destination"
              /bin/mv "$staged" "$destination"
            else
              /usr/bin/osascript \
                -e 'on run argv' \
                -e 'set sourcePath to item 1 of argv' \
                -e 'set destinationPath to item 2 of argv' \
                -e 'set commandText to "/bin/rm -rf " & quoted form of destinationPath & " && /usr/bin/ditto " & quoted form of sourcePath & " " & quoted form of destinationPath' \
                -e 'do shell script commandText with administrator privileges' \
                -e 'end run' \
                "$source_app" "$destination"
            fi
            cleanup
            trap - EXIT INT TERM
            /bin/rm -f "$installer"
            exec /usr/bin/open -n "$destination"
            """;

    private static final String LINUX_APPIMAGE_INSTALL_SCRIPT = """
            parent_pid="$1"
            installer="$2"
            destination="$3"
            set -e
            while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.1; done
            staged="${destination}.update-${parent_pid}"
            /bin/cp "$installer" "$staged"
            /bin/chmod 0755 "$staged"
            /bin/mv -f "$staged" "$destination"
            /bin/rm -f "$installer"
            trap - EXIT INT TERM
            exec "$destination"
            """;

    private static final String LINUX_PACKAGE_INSTALL_SCRIPT = """
            parent_pid="$1"
            installer="$2"
            package_kind="$3"
            launcher="$4"
            set -e
            while kill -0 "$parent_pid" 2>/dev/null; do sleep 0.1; done
            if test "$package_kind" = "deb"; then
              /usr/bin/pkexec /usr/bin/dpkg -i "$installer"
            else
              /usr/bin/pkexec /usr/bin/rpm -U --replacepkgs "$installer"
            fi
            /bin/rm -f "$installer"
            if test -x "$launcher"; then exec "$launcher"; fi
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Path appDirectory;
    private final Platform platform;
    private final Map<String, String> environment;
    private final ProcessStarter processStarter;
    private final Path downloadRoot;

    GitHubReleaseUpdateRuntime() {
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
                command -> new ProcessBuilder(command).start(),
                createDownloadRoot()
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
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.appDirectory = appDirectory.toAbsolutePath().normalize();
        this.platform = platform;
        this.environment = Map.copyOf(environment);
        this.processStarter = processStarter;
        this.downloadRoot = downloadRoot.toAbsolutePath().normalize();
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
        HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE_API)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Chat2DB-Community-Updater/" + currentVersion)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200 || !LATEST_RELEASE_API.equals(response.uri())) {
                throw new IOException("GitHub latest Release request failed: " + response.statusCode());
            }
            return parseLatestRelease(readBounded(input, MAX_API_RESPONSE_BYTES), currentVersion, platform, objectMapper);
        }
    }

    @Override
    public Path download(ReleaseInstaller release, DownloadProgress progress) throws Exception {
        Files.createDirectories(downloadRoot);
        Path versionDirectory = downloadRoot.resolve(release.version());
        Files.createDirectories(versionDirectory);
        Path target = versionDirectory.resolve(release.assetName());
        if (Files.isRegularFile(target) && verifyFile(target, release.size(), release.sha256())) {
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
        HttpResponse<InputStream> response = sendInstallerRequest(request);
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200 || !isAllowedDownloadResponse(response.uri())) {
                throw new IOException("GitHub Release installer download failed: " + response.statusCode());
            }
            Optional<String> contentLength = response.headers().firstValue("Content-Length");
            if (contentLength.isPresent() && Long.parseLong(contentLength.get()) != release.size()) {
                throw new IOException("GitHub Release installer size header does not match");
            }
            streamInstaller(input, partial, release, progress);
            moveAtomically(partial, target);
            return target;
        } catch (Exception exception) {
            Files.deleteIfExists(partial);
            throw exception;
        }
    }

    @Override
    public boolean launchInstaller(ReleaseInstaller release, Path installer, long parentPid) throws Exception {
        if (!Files.isRegularFile(installer) || !verifyFile(installer, release.size(), release.sha256())) {
            throw new IOException("Downloaded Community installer failed verification");
        }
        validateInstallerEnvironment(release);
        List<String> command = buildInstallerCommand(
                release,
                installer.toAbsolutePath().normalize(),
                parentPid,
                appDirectory,
                environment
        );
        processStarter.start(command);
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
        return switch (release.kind()) {
            case WINDOWS_MSI -> buildWindowsInstallerCommand(installer, parentPid, appDirectory);
            case MAC_DMG -> buildMacInstallerCommand(installer, parentPid, appDirectory);
            case LINUX_APPIMAGE -> buildAppImageInstallerCommand(installer, parentPid, environment);
            case LINUX_DEB -> buildLinuxPackageInstallerCommand(installer, parentPid, appDirectory, "deb");
            case LINUX_RPM -> buildLinuxPackageInstallerCommand(installer, parentPid, appDirectory, "rpm");
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

    private static List<String> buildWindowsInstallerCommand(Path installer, long parentPid, Path appDirectory) {
        Path launcher = findAncestorChild(appDirectory, "Chat2DB Community.exe");
        String arguments = "/i \"" + installer + "\" /passive /norestart";
        StringBuilder script = new StringBuilder("$ErrorActionPreference='Stop';")
                .append("Wait-Process -Id ").append(parentPid).append(" -ErrorAction SilentlyContinue;")
                .append("$msiexec=Join-Path $env:WINDIR 'System32\\msiexec.exe';")
                .append("if(!(Test-Path -LiteralPath $msiexec)){throw ")
                .append(powerShellLiteral("System MSI installer is unavailable")).append("};")
                .append("try{$process=Start-Process -FilePath $msiexec -ArgumentList ")
                .append(powerShellLiteral(arguments))
                .append(" -Verb RunAs -Wait -PassThru;")
                .append("if(@(0,1641,3010) -notcontains $process.ExitCode){throw ")
                .append(powerShellLiteral("MSI installation failed")).append("};")
                .append("Remove-Item -LiteralPath ").append(powerShellLiteral(installer.toString()))
                .append(" -Force -ErrorAction SilentlyContinue;");
        if (launcher != null) {
            script.append("if(Test-Path -LiteralPath ").append(powerShellLiteral(launcher.toString()))
                    .append("){Start-Process -FilePath ").append(powerShellLiteral(launcher.toString())).append("}}")
                    .append("catch{exit 1};");
        } else {
            script.append("}catch{exit 1};");
        }
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

    private static List<String> buildMacInstallerCommand(Path installer, long parentPid, Path appDirectory)
            throws IOException {
        Path bundle = findMacApplicationBundle(appDirectory);
        if (bundle == null || !isTrustedMacDestination(bundle)) {
            throw new IOException("Chat2DB Community must be installed in Applications before automatic updates");
        }
        return List.of(
                "/bin/sh",
                "-c",
                MAC_INSTALL_SCRIPT,
                "chat2db-community-update",
                Long.toString(parentPid),
                installer.toString(),
                bundle.toString()
        );
    }

    private static List<String> buildAppImageInstallerCommand(
            Path installer,
            long parentPid,
            Map<String, String> environment
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
                destination.toString()
        );
    }

    private static List<String> buildLinuxPackageInstallerCommand(
            Path installer,
            long parentPid,
            Path appDirectory,
            String packageKind
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
                launcher == null ? "" : launcher.toString()
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
            throw new IOException("GitHub Release installer verification failed");
        }
    }

    private static boolean verifyFile(Path file, long expectedSize, String expectedSha256) throws Exception {
        if (Files.size(file) != expectedSize) {
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
        return HexFormat.of().formatHex(digest.digest()).equals(expectedSha256);
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
            currentUri = nextUri;
        }
        throw new IOException("GitHub Release installer redirect limit exceeded");
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
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

    private static boolean isTrustedMacDestination(Path bundle) {
        Path normalized = bundle.toAbsolutePath().normalize();
        Path systemApplications = Path.of("/Applications").toAbsolutePath().normalize();
        Path userApplications = Path.of(System.getProperty("user.home"), "Applications").toAbsolutePath().normalize();
        return normalized.startsWith(systemApplications) || normalized.startsWith(userApplications);
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
