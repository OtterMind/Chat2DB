package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiRuntimeInstallerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsAndReusesAVerifiedRuntime() throws Exception {
        PiRuntimePaths paths = new PiRuntimePaths(temporaryDirectory.resolve("runtime/agent/pi"));
        URI source = URI.create("https://runtime.example/pi/");
        String os = PiRuntimeLayout.normalizeOperatingSystem(System.getProperty("os.name"));
        String architecture = PiRuntimeLayout.normalizeArchitecture(System.getProperty("os.arch"));
        String executable = "windows".equals(os) ? "pi.exe" : "pi";
        Map<String, byte[]> resources = resources(source, os, architecture, executable, "runtime");
        AtomicInteger downloads = new AtomicInteger();
        PiRuntimeInstaller installer = new PiRuntimeInstaller(
                paths, "0.85.1", source, trust(resources), (uri, maximumBytes) -> {
                    downloads.incrementAndGet();
                    byte[] bytes = resources.get(uri.toString());
                    if (bytes == null) throw new java.io.IOException("missing resource");
                    return bytes;
                });

        Path installed = installer.install(environment());
        int firstDownloadCount = downloads.get();
        assertEquals(installed, installer.install(environment()));

        assertEquals("runtime", Files.readString(installed.resolve(executable)));
        assertEquals(firstDownloadCount, downloads.get());
        assertFalse(Files.exists(paths.temporary()) && hasChildren(paths.temporary()));
    }

    @Test
    void rejectsHashMismatchAndCleansStaging() throws Exception {
        PiRuntimePaths paths = new PiRuntimePaths(temporaryDirectory.resolve("runtime/agent/pi"));
        URI source = URI.create("https://runtime.example/pi/");
        String os = PiRuntimeLayout.normalizeOperatingSystem(System.getProperty("os.name"));
        String architecture = PiRuntimeLayout.normalizeArchitecture(System.getProperty("os.arch"));
        String executable = "windows".equals(os) ? "pi.exe" : "pi";
        Map<String, byte[]> resources = resources(source, os, architecture, executable, "expected");
        String fileUri = source.resolve("0.85.1/" + os + "-" + architecture + "/" + executable).toString();
        resources.put(fileUri, "changed".getBytes());
        PiRuntimeInstaller installer = new PiRuntimeInstaller(
                paths, "0.85.1", source, trust(resources),
                (uri, maximumBytes) -> resources.get(uri.toString()));

        assertThrows(java.io.IOException.class, () -> installer.install(environment()));

        assertFalse(Files.exists(paths.installations().resolve("0.85.1")));
        assertFalse(Files.exists(paths.temporary()) && hasChildren(paths.temporary()));
    }

    @Test
    void requiresHttpsDownloadSource() {
        assertThrows(IllegalArgumentException.class, () -> new PiRuntimeInstaller(
                new PiRuntimePaths(temporaryDirectory), "0.85.1", URI.create("http://runtime.example/pi/"),
                (platform, manifest) -> { }));
    }

    @Test
    void rejectsAManifestThatDoesNotMatchThePinnedDigest() throws Exception {
        PiRuntimePaths paths = new PiRuntimePaths(temporaryDirectory.resolve("runtime/agent/pi"));
        URI source = URI.create("https://runtime.example/pi/");
        String os = PiRuntimeLayout.normalizeOperatingSystem(System.getProperty("os.name"));
        String architecture = PiRuntimeLayout.normalizeArchitecture(System.getProperty("os.arch"));
        String executable = "windows".equals(os) ? "pi.exe" : "pi";
        Map<String, byte[]> resources = resources(source, os, architecture, executable, "runtime");
        PiRuntimeInstaller installer = new PiRuntimeInstaller(
                paths, "0.85.1", source,
                new PinnedPiRuntimeManifestTrust(platform -> "0".repeat(64)),
                (uri, maximumBytes) -> resources.get(uri.toString()));

        assertThrows(java.io.IOException.class, () -> installer.install(environment()));
        assertFalse(Files.exists(paths.installations().resolve("0.85.1")));
    }

    private Map<String, byte[]> resources(
            URI source, String os, String architecture, String executable, String executableContent) throws Exception {
        byte[] runtime = executableContent.getBytes();
        byte[] asset = "asset".getBytes();
        Map<String, String> files = new LinkedHashMap<>();
        files.put(executable, sha256(runtime));
        files.put("assets/data.txt", sha256(asset));
        PiRuntimeManifest manifest = new PiRuntimeManifest(
                "0.85.1", os, architecture, "rpc-v1", "pi-release", files);
        String platform = "0.85.1/" + os + "-" + architecture + "/";
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put(source.resolve(platform + "runtime-manifest.json").toString(),
                new ObjectMapper().writeValueAsBytes(manifest));
        resources.put(source.resolve(platform + executable).toString(), runtime);
        resources.put(source.resolve(platform + "assets/data.txt").toString(), asset);
        return resources;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private PiRuntimeManifestTrust trust(Map<String, byte[]> resources) {
        byte[] manifest = null;
        for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
            if (resource.getKey().endsWith("runtime-manifest.json")) {
                manifest = resource.getValue();
                break;
            }
        }
        if (manifest == null) {
            throw new AssertionError("manifest fixture is missing");
        }
        byte[] trustedManifest = manifest;
        return new PinnedPiRuntimeManifestTrust(platform -> {
            try {
                return sha256(trustedManifest);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    private AgentRuntimeEnvironmentRequest environment() {
        return new AgentRuntimeEnvironmentRequest(
                "5.3.0", System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    private boolean hasChildren(Path directory) throws Exception {
        try (var children = Files.list(directory)) {
            return children.findAny().isPresent();
        }
    }
}
