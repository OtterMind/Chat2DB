package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiRuntimeInstallerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsAnOfficialStyleArchiveAndReusesIt() throws Exception {
        PiRuntimePaths paths = new PiRuntimePaths(temporaryDirectory.resolve("runtime/agent/pi"));
        byte[] archive = archive(Map.of("pi/pi", "runtime", "pi/assets/data.txt", "asset"));
        String archiveSha256 = sha256(archive);
        AtomicInteger downloads = new AtomicInteger();
        PiRuntimeInstaller installer = installer(paths, archive, (platform, bytes) -> {
            try {
                assertEquals(archiveSha256, sha256(bytes));
            } catch (Exception error) {
                throw new java.io.IOException(error);
            }
        }, downloads);

        Path installed = installer.install(environment());
        int firstDownloadCount = downloads.get();
        assertEquals(installed, installer.install(environment()));

        assertEquals("runtime", Files.readString(installed.resolve(executableName())));
        assertEquals("asset", Files.readString(installed.resolve("assets/data.txt")));
        assertEquals(firstDownloadCount, downloads.get());
        assertFalse(Files.exists(paths.temporary()) && hasChildren(paths.temporary()));
    }

    @Test
    void rejectsAnArchiveThatDoesNotMatchThePinnedDigest() throws Exception {
        PiRuntimePaths paths = new PiRuntimePaths(temporaryDirectory.resolve("runtime/agent/pi"));
        byte[] archive = archive(Map.of("pi/pi", "runtime"));
        PiRuntimeInstaller installer = installer(
                paths,
                archive,
                new PinnedPiRuntimeArchiveTrust(platform -> "0".repeat(64)),
                new AtomicInteger());

        assertThrows(java.io.IOException.class, () -> installer.install(environment()));
        assertFalse(Files.exists(paths.installations().resolve("0.85.1")));
    }

    @Test
    void rejectsArchivePathTraversalAndCleansStaging() throws Exception {
        PiRuntimePaths paths = new PiRuntimePaths(temporaryDirectory.resolve("runtime/agent/pi"));
        byte[] archive = archive(Map.of("pi/pi", "runtime", "pi/../../outside", "unsafe"));
        PiRuntimeInstaller installer = installer(paths, archive, (platform, bytes) -> { }, new AtomicInteger());

        assertThrows(java.io.IOException.class, () -> installer.install(environment()));
        assertFalse(Files.exists(temporaryDirectory.resolve("outside")));
        assertFalse(Files.exists(paths.temporary()) && hasChildren(paths.temporary()));
    }

    @Test
    void requiresHttpsDownloadSource() {
        assertThrows(IllegalArgumentException.class, () -> new PiRuntimeInstaller(
                new PiRuntimePaths(temporaryDirectory), "0.85.1", URI.create("http://runtime.example/pi/"),
                (platform, archive) -> { }));
    }

    private PiRuntimeInstaller installer(
            PiRuntimePaths paths,
            byte[] archive,
            PiRuntimeArchiveTrust trust,
            AtomicInteger downloads) {
        return new PiRuntimeInstaller(
                paths,
                "0.85.1",
                URI.create("https://runtime.example/pi/"),
                trust,
                (uri, maximumBytes) -> {
                    downloads.incrementAndGet();
                    return archive;
                });
    }

    private byte[] archive(Map<String, String> files) throws Exception {
        return "windows".equals(currentOs()) ? zip(files) : tarGzip(files);
    }

    private byte[] tarGzip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream output = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR);
            for (Map.Entry<String, String> file : new LinkedHashMap<>(files).entrySet()) {
                byte[] content = file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(content.length);
                output.putArchiveEntry(entry);
                output.write(content);
                output.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                output.putNextEntry(new ZipEntry(file.getKey().replace("pi/pi", "pi/pi.exe")));
                output.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private AgentRuntimeEnvironmentRequest environment() {
        return new AgentRuntimeEnvironmentRequest(
                "5.3.0", System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private String currentOs() {
        return PiRuntimeLayout.normalizeOperatingSystem(System.getProperty("os.name"));
    }

    private String executableName() {
        return "windows".equals(currentOs()) ? "pi.exe" : "pi";
    }

    private boolean hasChildren(Path directory) throws Exception {
        try (var children = Files.list(directory)) {
            return children.findAny().isPresent();
        }
    }
}
