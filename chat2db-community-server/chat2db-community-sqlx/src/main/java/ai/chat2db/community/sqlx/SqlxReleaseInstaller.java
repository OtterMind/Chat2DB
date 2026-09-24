package ai.chat2db.community.sqlx;

import ai.chat2db.community.tools.sqlx.SqlxException;
import ai.chat2db.community.tools.util.ConfigUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the official SQLX release archive and installs it like the published installers do:
 * resolve the version, download the archive and {@code SHA256SUMS}, verify the digest, extract the
 * executable, confirm the {@code (OtterMind/sqlx)} marker, then move it into place atomically.
 */
public final class SqlxReleaseInstaller {

    public static final String DEFAULT_RELEASE_BASE = "https://github.com/OtterMind/sqlx/releases";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int BUFFER = 16 * 1024;
    private static final long PROGRESS_INTERVAL = 256 * 1024;

    /** Steps reported while an installation runs. */
    public interface Progress {

        void step(String step);

        void downloading(long downloaded, long total);
    }

    /**
     * @param note why the executable did not land in the official directory, or {@code null}.
     */
    public record Installed(Path executable, String version, String sha256, String note) {
    }

    private final String releaseBase;
    private final Path installDirectoryOverride;
    private final Path managedDirectoryOverride;
    private final HttpClient http;

    public SqlxReleaseInstaller() {
        this(DEFAULT_RELEASE_BASE, null);
    }

    public SqlxReleaseInstaller(String releaseBase) {
        this(releaseBase, null);
    }

    /**
     * @param installDirectoryOverride install somewhere other than the user-level directory, used by tests.
     */
    public SqlxReleaseInstaller(String releaseBase, Path installDirectoryOverride) {
        this(releaseBase, installDirectoryOverride, null);
    }

    /**
     * @param managedDirectoryOverride the fallback directory, used by tests.
     */
    public SqlxReleaseInstaller(String releaseBase, Path installDirectoryOverride, Path managedDirectoryOverride) {
        this.releaseBase = releaseBase.replaceAll("/+$", "");
        this.installDirectoryOverride = installDirectoryOverride;
        this.managedDirectoryOverride = managedDirectoryOverride;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Latest stable version published by the release channel. */
    public String latestVersion() {
        String body = fetchText(releaseBase + "/latest/download/release-version.txt");
        String version = body.trim();
        if (!SqlxExecutable.isValidVersion(version)) {
            throw new SqlxException("sqlx.invalid_release", "the release channel returned an invalid version: " + body);
        }
        return version;
    }

    public Installed install(String requestedVersion, SqlxPlatform platform, Progress progress,
            BooleanSupplier cancelled) {
        String version = requestedVersion == null || requestedVersion.isBlank()
                ? latestVersion()
                : requestedVersion.trim();
        if (!SqlxExecutable.isValidVersion(version)) {
            throw new SqlxException("sqlx.invalid_release", "invalid SQLX version: " + requestedVersion);
        }
        String prefix = releaseBase + "/download/v" + version;
        String asset = platform.assetName();
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("chat2db-sqlx-");
            Path archive = temporary.resolve(asset);
            checkCancelled(cancelled);
            download(prefix + "/" + asset, archive, progress, cancelled);
            step(progress, "verifying");
            checkCancelled(cancelled);
            String expected = checksumFor(fetchText(prefix + "/SHA256SUMS"), asset);
            if (expected == null) {
                throw new SqlxException("sqlx.checksum_missing",
                        "SHA256SUMS does not list " + asset + " for version " + version);
            }
            String actual = sha256(archive);
            if (!expected.equalsIgnoreCase(actual)) {
                throw new SqlxException("sqlx.checksum_mismatch",
                        "the downloaded archive does not match SHA256SUMS");
            }
            step(progress, "extracting");
            Path staged = temporary.resolve(platform.executableName());
            extractEntry(archive, platform.executableName(), staged);
            makeExecutable(staged);
            step(progress, "validating");
            if (!SqlxExecutable.isOtterMind(staged)) {
                throw new SqlxException("sqlx.invalid_binary",
                        "the archive does not contain an OtterMind SQLX executable");
            }
            step(progress, "installing");
            Path target;
            String note = null;
            try {
                target = installInto(primaryDirectory(platform), platform, staged, cancelled);
            } catch (IOException | SqlxException failure) {
                // The official directory can be read-only or hold a binary another process keeps open,
                // so degrade to a directory this application owns instead of failing the install.
                Path managed = managedDirectory(version);
                note = "could not install into " + primaryDirectory(platform) + " (" + firstLine(failure)
                        + "); installed into " + managed;
                target = installInto(managed, platform, staged, cancelled);
            }
            return new Installed(target, version, actual, note);
        } catch (IOException exception) {
            throw new SqlxException("sqlx.install_failed", exception.getMessage(), exception);
        } finally {
            deleteRecursively(temporary);
        }
    }

    /** The user-level directory the official installers use, or the override a test asked for. */
    private Path primaryDirectory(SqlxPlatform platform) {
        return installDirectoryOverride == null ? platform.installDirectory() : installDirectoryOverride;
    }

    /** Directory this application owns, used when the official one cannot be written. */
    private Path managedDirectory(String version) {
        return managedDirectoryOverride == null
                ? Path.of(ConfigUtils.getBasePath(), "tools", "sqlx", version)
                : managedDirectoryOverride;
    }

    private static String firstLine(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** Move the verified executable into the given directory. */
    private Path installInto(Path directory, SqlxPlatform platform, Path staged, BooleanSupplier cancelled)
            throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(platform.executableName());
        if (Files.isSymbolicLink(target)) {
            throw new SqlxException("sqlx.install_conflict",
                    target + " is a symbolic link; choose another installation directory");
        }
        if (Files.exists(target) && !SqlxExecutable.isOtterMind(target)) {
            throw new SqlxException("sqlx.install_conflict",
                    "another program named " + platform.executableName() + " already exists at " + target);
        }
        checkCancelled(cancelled);
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
        makeExecutable(target);
        return target;
    }

    private void download(String url, Path target, Progress progress, BooleanSupplier cancelled) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new SqlxException("sqlx.download_failed",
                        "the release channel answered " + response.statusCode() + " for " + url);
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1);
            long downloaded = 0;
            long reported = 0;
            byte[] buffer = new byte[BUFFER];
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(target)) {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    checkCancelled(cancelled);
                    output.write(buffer, 0, read);
                    downloaded += read;
                    if (downloaded - reported >= PROGRESS_INTERVAL) {
                        reported = downloaded;
                        downloading(progress, downloaded, total);
                    }
                }
            }
            downloading(progress, downloaded, total);
        } catch (IOException exception) {
            throw new SqlxException("sqlx.download_failed", exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SqlxException("sqlx.cancelled", "the download was interrupted", exception);
        }
    }

    private String fetchText(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new SqlxException("sqlx.download_failed",
                        "the release channel answered " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (IOException exception) {
            throw new SqlxException("sqlx.download_failed", exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SqlxException("sqlx.cancelled", "the request was interrupted", exception);
        }
    }

    /** Digest listed for the asset in a {@code SHA256SUMS} document, or {@code null}. */
    static String checksumFor(String sha256Sums, String asset) {
        for (String line : sha256Sums.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String name = parts[parts.length - 1].replaceFirst("^\\*", "");
            if (name.equals(asset)) {
                return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Copy the executable out of the release archive, ignoring the bundled licence files. */
    static void extractEntry(Path archive, String entryName, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (fileName.equals(entryName)) {
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new SqlxException("sqlx.invalid_archive", "the archive does not contain " + entryName);
    }

    static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // impl-contract: Windows has no POSIX permissions; the executable bit is not needed there.
        }
    }

    private static void step(Progress progress, String step) {
        if (progress != null) {
            progress.step(step);
        }
    }

    private static void downloading(Progress progress, long downloaded, long total) {
        if (progress != null) {
            progress.downloading(downloaded, total);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new SqlxException("sqlx.cancelled", "the installation was cancelled");
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // impl-contract: a leftover temporary file is cleaned by the operating system.
                }
            });
        } catch (IOException ignored) {
            // impl-contract: cleanup must never mask the installation result.
        }
    }
}
