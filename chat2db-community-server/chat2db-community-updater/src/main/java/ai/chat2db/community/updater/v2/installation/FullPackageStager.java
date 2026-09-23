package ai.chat2db.community.updater.v2.installation;

import ai.chat2db.community.updater.v2.model.UpdateManifest;
import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePlatformEnum;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class FullPackageStager {

    /** Records which package a staging directory was produced from. */
    static final String SOURCE_SHA_MARKER = ".source-sha256";
    private static final int MAX_ENTRY_COUNT = 100_000;
    private static final long EXTRACT_TIMEOUT_SECONDS = 180;

    public Path stage(Path downloadedPackage, UpdateManifest manifest, UpdateLayout layout) {
        Path stagingRoot = layout.stagingDirectory();
        try {
            Path staged = reuseStagedPackage(stagingRoot, manifest, layout);
            if (staged != null) {
                return staged;
            }
            deleteRecursively(stagingRoot);
            Files.createDirectories(stagingRoot);
            Path candidate;
            if (manifest.packageType() != UpdatePackageTypeEnum.MACOS_APP_ARCHIVE) {
                candidate = layout.stagedPackage(manifest.packageType());
                Files.copy(downloadedPackage, candidate, StandardCopyOption.REPLACE_EXISTING);
                if (manifest.packageType() == UpdatePackageTypeEnum.LINUX_APPIMAGE) {
                    setExecutable(candidate);
                }
            } else {
                validateArchiveEntries(downloadedPackage);
                extractArchive(downloadedPackage, stagingRoot);
                candidate = stagingRoot.resolve("package");
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Full package archive must contain one package directory");
                }
                validateSymlinks(candidate);
            }
            if (manifest.packageType().directReplacement()) {
                requireCandidateLauncher(candidate, manifest);
            }
            Files.writeString(stagingRoot.resolve(SOURCE_SHA_MARKER), manifest.packageSha256());
            return candidate;
        } catch (Exception exception) {
            try {
                deleteRecursively(stagingRoot);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Cannot stage full update package", exception);
        }
    }

    private static void validateArchiveEntries(Path archive) throws Exception {
        Process process = new ProcessBuilder(tarExecutable(), "-tzf", archive.toString())
            .redirectErrorStream(true)
            .start();
        int count = 0;
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            process.getInputStream(), StandardCharsets.UTF_8))) {
            String entry;
            while ((entry = reader.readLine()) != null) {
                if (output.length() < 4096) {
                    output.append(entry).append('\n');
                }
                if (++count > MAX_ENTRY_COUNT) {
                    process.destroyForcibly();
                    throw new IOException("Full package archive contains too many entries");
                }
                String normalized = entry.replace('\\', '/');
                if (normalized.startsWith("/") || normalized.contains("/../")
                        || normalized.equals("..") || normalized.startsWith("../")
                        || !(normalized.equals("package") || normalized.startsWith("package/"))) {
                    process.destroyForcibly();
                    throw new IOException("Full package archive contains an unsafe entry: " + entry);
                }
            }
        }
        if (!process.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out while listing full package archive");
        }
        if (process.exitValue() != 0 || count == 0) {
            throw new IOException("Cannot list full package archive: " + output.toString().trim());
        }
    }

    private static void extractArchive(Path archive, Path stagingRoot) throws Exception {
        Process process = new ProcessBuilder(
            tarExecutable(), "-xzf", archive.toString(), "-C", stagingRoot.toString()
        )
            .redirectErrorStream(true)
            .start();
        byte[] output = process.getInputStream().readNBytes(8192);
        if (!process.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out while extracting full package archive");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Cannot extract full package archive: "
                + new String(output, StandardCharsets.UTF_8).trim());
        }
    }

    private static void validateSymlinks(Path packageRoot) throws IOException {
        try (var paths = Files.walk(packageRoot)) {
            for (Path path : paths.toList()) {
                if (!Files.isSymbolicLink(path)) {
                    continue;
                }
                Path linkTarget = Files.readSymbolicLink(path);
                Path resolved = linkTarget.isAbsolute()
                    ? linkTarget.normalize()
                    : path.getParent().resolve(linkTarget).normalize();
                if (linkTarget.isAbsolute() || !resolved.startsWith(packageRoot)) {
                    throw new IOException("Full package contains a symlink outside the package: " + path);
                }
            }
        }
    }

    /**
     * Returns the already staged package when the staging directory was produced from
     * exactly this package, so a restart does not unpack the same archive again. The
     * staged content is re-validated before it is trusted.
     */
    private Path reuseStagedPackage(Path stagingRoot, UpdateManifest manifest, UpdateLayout layout)
            throws IOException {
        Path marker = stagingRoot.resolve(SOURCE_SHA_MARKER);
        if (!Files.isRegularFile(marker)
                || !Files.readString(marker).trim().equalsIgnoreCase(manifest.packageSha256())) {
            return null;
        }
        Path candidate = manifest.packageType() == UpdatePackageTypeEnum.MACOS_APP_ARCHIVE
            ? stagingRoot.resolve("package")
            : layout.stagedPackage(manifest.packageType());
        boolean present = manifest.packageType() == UpdatePackageTypeEnum.MACOS_APP_ARCHIVE
            ? Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
            : Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS);
        if (!present) {
            return null;
        }
        if (manifest.packageType() == UpdatePackageTypeEnum.MACOS_APP_ARCHIVE) {
            validateSymlinks(candidate);
        }
        if (manifest.packageType().directReplacement()) {
            requireCandidateLauncher(candidate, manifest);
        }
        return candidate;
    }

    private static void requireCandidateLauncher(Path candidate, UpdateManifest manifest) throws IOException {
        Path launcher = manifest.packageType().singleFile()
            ? candidate
            : candidate.resolve(manifest.launcherRelativePath()).normalize();
        if (!launcher.startsWith(candidate) || !Files.isRegularFile(launcher)) {
            throw new IOException("Full package candidate launcher is missing: " + launcher);
        }
        if (manifest.platform() != UpdatePlatformEnum.WINDOWS && !Files.isExecutable(launcher)) {
            throw new IOException("Full package candidate launcher is not executable: " + launcher);
        }
    }

    private static String tarExecutable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "tar.exe" : "tar";
    }

    private static void setExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(file));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!file.toFile().setExecutable(true, false)) {
                throw new IOException("Cannot make AppImage executable: " + file);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
