package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PiRuntimeInstaller implements PiRuntimeInstallation {

    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 768L * 1024 * 1024;
    private static final int MAX_FILES = 5000;

    private final PiRuntimePaths paths;
    private final String version;
    private final URI sourceRoot;
    private final PiRuntimeArchiveTrust archiveTrust;
    private final ResourceFetcher fetcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PiRuntimeInstaller(
            PiRuntimePaths paths,
            String version,
            URI sourceRoot,
            PiRuntimeArchiveTrust archiveTrust) {
        this(paths, version, sourceRoot, archiveTrust,
                new HttpResourceFetcher(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()));
    }

    PiRuntimeInstaller(
            PiRuntimePaths paths,
            String version,
            URI sourceRoot,
            PiRuntimeArchiveTrust archiveTrust,
            ResourceFetcher fetcher) {
        if (!"https".equalsIgnoreCase(sourceRoot.getScheme())) {
            throw new IllegalArgumentException("Pi runtime source must use HTTPS");
        }
        this.paths = paths;
        this.version = version;
        this.sourceRoot = sourceRoot.toString().endsWith("/") ? sourceRoot : URI.create(sourceRoot + "/");
        this.archiveTrust = archiveTrust;
        this.fetcher = fetcher;
    }

    @Override
    public synchronized Path install(AgentRuntimeEnvironmentRequest environment) throws IOException {
        String os = PiRuntimeLayout.normalizeOperatingSystem(environment.operatingSystem());
        String architecture = PiRuntimeLayout.normalizeArchitecture(environment.architecture());
        String platform = os + "-" + architecture;
        PiRuntimeLayout finalLayout = new PiRuntimeLayout(paths.installations(), version);
        Path target = finalLayout.platformDirectory(os, architecture);
        if (new PiRuntimeEnvironmentChecker(finalLayout).inspect(environment).isUsable()) {
            return target;
        }

        Path stagingRoot = paths.temporary().resolve(UUID.randomUUID().toString()).normalize();
        Path staging = stagingRoot.resolve(version).resolve(platform).normalize();
        try {
            Files.createDirectories(staging);
            String assetName = assetName(os, architecture);
            byte[] archive = fetcher.fetch(sourceRoot.resolve(assetName), MAX_ARCHIVE_BYTES);
            archiveTrust.verify(platform, archive);
            if (assetName.endsWith(".zip")) {
                extractZip(archive, staging);
            } else {
                extractTarGzip(archive, staging);
            }
            Path executable = staging.resolve("windows".equals(os) ? "pi.exe" : "pi");
            if (!"windows".equals(os) && !executable.toFile().setExecutable(true, true)) {
                throw new IOException("Cannot make Pi runtime executable");
            }
            writeLocalManifest(staging, os, architecture, assetName);
            AgentRuntimeEnvironmentReport report = new PiRuntimeEnvironmentChecker(
                    new PiRuntimeLayout(stagingRoot, version)).inspect(environment);
            if (report.status() != AgentRuntimeEnvironmentStatus.READY) {
                throw new IOException("Downloaded Pi runtime failed verification: "
                        + report.diagnostics().getOrDefault("reason", "unknown reason"));
            }
            publish(stagingRoot, staging, target);
            return target;
        } catch (IOException | RuntimeException error) {
            deleteTree(stagingRoot);
            throw error;
        }
    }

    private String assetName(String os, String architecture) throws IOException {
        return switch (os) {
            case "macos" -> "pi-darwin-" + architecture + ".tar.gz";
            case "linux" -> "pi-linux-" + architecture + ".tar.gz";
            case "windows" -> "pi-windows-" + architecture + ".zip";
            default -> throw new IOException("Pi runtime is unsupported on " + os + "-" + architecture);
        };
    }

    private void extractTarGzip(byte[] archive, Path target) throws IOException {
        ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
        try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(new ByteArrayInputStream(archive))) {
            gzip.transferTo(tarBytes);
        }
        try (TarArchiveInputStream input = new TarArchiveInputStream(
                new ByteArrayInputStream(tarBytes.toByteArray()))) {
            long extractedBytes = 0;
            int fileCount = 0;
            TarArchiveEntry entry;
            while ((entry = input.getNextTarEntry()) != null) {
                if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
                        || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new IOException("Pi runtime archive contains an unsupported entry");
                }
                Path output = resolveEntry(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                extractedBytes += entry.getSize();
                if (++fileCount > MAX_FILES || extractedBytes > MAX_EXTRACTED_BYTES) {
                    throw new IOException("Pi runtime archive exceeds extraction limits");
                }
                Files.createDirectories(output.getParent());
                Files.copy(input, output);
            }
        }
    }

    private void extractZip(byte[] archive, Path target) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            long extractedBytes = 0;
            int fileCount = 0;
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = resolveEntry(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                if (++fileCount > MAX_FILES) {
                    throw new IOException("Pi runtime archive exceeds its file limit");
                }
                Files.createDirectories(output.getParent());
                try (LimitedOutputStream limited = new LimitedOutputStream(
                        Files.newOutputStream(output), MAX_EXTRACTED_BYTES - extractedBytes)) {
                    input.transferTo(limited);
                    extractedBytes += limited.written();
                }
            }
        }
    }

    private Path resolveEntry(Path target, String archiveName) throws IOException {
        Path relative = Path.of(archiveName.replace('\\', '/')).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.getNameCount() < 1
                || !"pi".equals(relative.getName(0).toString())) {
            throw new IOException("Pi runtime archive contains an unsafe path");
        }
        if (relative.getNameCount() == 1) {
            return target;
        }
        Path output = target.resolve(relative.subpath(1, relative.getNameCount())).normalize();
        if (!output.startsWith(target)) {
            throw new IOException("Pi runtime archive path escapes its target");
        }
        return output;
    }

    private void writeLocalManifest(Path directory, String os, String architecture, String assetName)
            throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (var entries = Files.walk(directory)) {
            for (Path file : entries
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList()) {
                files.put(directory.relativize(file).toString().replace('\\', '/'), sha256(file));
            }
        }
        PiRuntimeManifest manifest = new PiRuntimeManifest(
                version, os, architecture, "jsonl-rpc", sourceRoot.resolve(assetName).toString(), files);
        objectMapper.writeValue(directory.resolve("runtime-manifest.json").toFile(), manifest);
    }

    private void publish(Path stagingRoot, Path staging, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path backup = stagingRoot.resolve("previous");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            move(target, backup);
        }
        try {
            move(staging, target);
        } catch (IOException error) {
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                move(backup, target);
            }
            throw error;
        }
        try {
            deleteTree(stagingRoot);
        } catch (IOException ignored) {
            // The verified target is already published; a later install can clean its private temp root.
        }
    }

    private String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target);
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    @FunctionalInterface
    interface ResourceFetcher {
        byte[] fetch(URI uri, long maximumBytes) throws IOException;
    }

    private record HttpResourceFetcher(HttpClient client) implements ResourceFetcher {
        @Override
        public byte[] fetch(URI uri, long maximumBytes) throws IOException {
            try {
                HttpResponse<byte[]> response = client.send(
                        HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(20))
                                .header("Accept-Encoding", "identity")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("Pi runtime download failed with HTTP " + response.statusCode());
                }
                if (response.body().length > maximumBytes) {
                    throw new IOException("Pi runtime download exceeds the size limit");
                }
                return response.body();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Pi runtime download was interrupted", error);
            }
        }
    }

    private static final class LimitedOutputStream extends java.io.FilterOutputStream {
        private final long limit;
        private long written;

        private LimitedOutputStream(java.io.OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireCapacity(length);
            out.write(bytes, offset, length);
            written += length;
        }

        private void requireCapacity(int length) throws IOException {
            if (written + length > limit) {
                throw new IOException("Pi runtime archive exceeds extraction limits");
            }
        }

        private long written() {
            return written;
        }
    }
}
