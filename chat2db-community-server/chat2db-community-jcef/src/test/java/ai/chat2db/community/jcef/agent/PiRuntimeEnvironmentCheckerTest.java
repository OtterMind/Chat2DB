package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiRuntimeEnvironmentCheckerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsACompleteVerifiedRuntime() throws Exception {
        Path directory = createRuntime();

        AgentRuntimeEnvironmentReport report = checker().inspect(environment());

        assertEquals(AgentRuntimeEnvironmentStatus.READY, report.status());
        assertEquals("macos", report.operatingSystem());
        assertEquals("arm64", report.architecture());
        assertEquals(3, report.checks().size());
        assertTrue(Files.isExecutable(directory.resolve("pi")));
    }

    @Test
    void blocksAChangedRuntimeFile() throws Exception {
        Path directory = createRuntime();
        Files.writeString(directory.resolve("asset.txt"), "changed");

        AgentRuntimeEnvironmentReport report = checker().inspect(environment());

        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED, report.status());
        assertTrue(report.diagnostics().get("reason").contains("hash mismatch"));
    }

    @Test
    void blocksMissingOrPlatformMismatchedRuntime() throws Exception {
        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED, checker().inspect(environment()).status());

        Path directory = createRuntime();
        PiRuntimeManifest manifest = new ObjectMapper().readValue(
                directory.resolve("runtime-manifest.json").toFile(), PiRuntimeManifest.class);
        writeManifest(directory, new PiRuntimeManifest(
                manifest.version(), "linux", manifest.architecture(),
                manifest.protocolVersion(), manifest.source(), manifest.files()));

        AgentRuntimeEnvironmentReport report = checker().inspect(environment());
        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED, report.status());
        assertTrue(report.diagnostics().get("reason").contains("operating system"));
    }

    @Test
    void rejectsManifestTraversal() throws Exception {
        Path directory = createRuntime();
        writeManifest(directory, new PiRuntimeManifest(
                "0.85.1", "macos", "arm64", "rpc-v1", "pi-release",
                Map.of("../outside", "0".repeat(64))));

        AgentRuntimeEnvironmentReport report = checker().inspect(environment());

        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED, report.status());
        assertTrue(report.diagnostics().get("reason").contains("unsafe file path"));
    }

    @Test
    void requiresTheExecutableToBeCoveredByTheManifest() throws Exception {
        Path directory = createRuntime();
        Path asset = directory.resolve("asset.txt");
        writeManifest(directory, new PiRuntimeManifest(
                "0.85.1", "macos", "arm64", "rpc-v1", "pi-release",
                Map.of("asset.txt", sha256(asset))));

        AgentRuntimeEnvironmentReport report = checker().inspect(environment());

        assertEquals(AgentRuntimeEnvironmentStatus.BLOCKED, report.status());
        assertTrue(report.diagnostics().get("reason").contains("executable"));
    }

    private Path createRuntime() throws Exception {
        Path directory = temporaryDirectory.resolve("0.85.1/macos-arm64");
        Files.createDirectories(directory);
        Path executable = directory.resolve("pi");
        Path asset = directory.resolve("asset.txt");
        Files.writeString(executable, "runtime");
        executable.toFile().setExecutable(true, true);
        Files.writeString(asset, "asset");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pi", sha256(executable));
        files.put("asset.txt", sha256(asset));
        writeManifest(directory, new PiRuntimeManifest(
                "0.85.1", "macos", "arm64", "rpc-v1", "pi-release", files));
        return directory;
    }

    private void writeManifest(Path directory, PiRuntimeManifest manifest) throws IOException {
        new ObjectMapper().writeValue(directory.resolve("runtime-manifest.json").toFile(), manifest);
    }

    private String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private PiRuntimeEnvironmentChecker checker() {
        return new PiRuntimeEnvironmentChecker(new PiRuntimeLayout(temporaryDirectory, "0.85.1"));
    }

    private AgentRuntimeEnvironmentRequest environment() {
        return new AgentRuntimeEnvironmentRequest("5.3.0", "Mac OS X", "aarch64");
    }
}
