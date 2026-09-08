package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PiRuntimeEnvironmentChecker {

    private final PiRuntimeLayout layout;
    private final PiRuntimeManifestReader manifestReader;
    private final Clock clock;

    public PiRuntimeEnvironmentChecker(PiRuntimeLayout layout) {
        this(layout, new PiRuntimeManifestReader(), Clock.systemDefaultZone());
    }

    PiRuntimeEnvironmentChecker(PiRuntimeLayout layout, PiRuntimeManifestReader manifestReader, Clock clock) {
        this.layout = layout;
        this.manifestReader = manifestReader;
        this.clock = clock;
    }

    public AgentRuntimeEnvironmentReport inspect(AgentRuntimeEnvironmentRequest request) {
        String os = PiRuntimeLayout.normalizeOperatingSystem(request.operatingSystem());
        String architecture = PiRuntimeLayout.normalizeArchitecture(request.architecture());
        List<String> checks = new ArrayList<>();
        Map<String, String> diagnostics = new LinkedHashMap<>();
        try {
            Path directory = layout.platformDirectory(os, architecture);
            Path manifestFile = directory.resolve("runtime-manifest.json");
            if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Pi runtime manifest is missing or unsafe");
            }
            PiRuntimeManifest manifest = manifestReader.read(directory);
            requireEqual(layout.version(), manifest.version(), "runtime version");
            requireEqual(os, manifest.operatingSystem(), "operating system");
            requireEqual(architecture, manifest.architecture(), "architecture");
            checks.add("MANIFEST_VALID");
            String executableName = "windows".equals(os) ? "pi.exe" : "pi";
            if (!manifest.files().containsKey(executableName)) {
                throw new IOException("Pi runtime manifest does not include its executable");
            }
            verifyFiles(directory, manifest.files());
            checks.add("FILES_VERIFIED");
            Path executable = layout.executable(os, architecture);
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                    || (!"windows".equals(os) && !Files.isExecutable(executable))) {
                throw new IOException("Pi runtime executable is missing or not executable");
            }
            checks.add("EXECUTABLE_READY");
            return report(AgentRuntimeEnvironmentStatus.READY, os, architecture, checks, diagnostics);
        } catch (IOException | RuntimeException error) {
            diagnostics.put("reason", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            return report(AgentRuntimeEnvironmentStatus.BLOCKED, os, architecture, checks, diagnostics);
        }
    }

    private void verifyFiles(Path directory, Map<String, String> files) throws IOException {
        Path realDirectory = directory.toRealPath();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path relative = Path.of(entry.getKey()).normalize();
            Path file = directory.resolve(relative).normalize();
            if (!file.startsWith(directory)
                    || containsSymbolicLink(directory, relative)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().startsWith(realDirectory)) {
                throw new IOException("Pi runtime file is missing or unsafe: " + entry.getKey());
            }
            if (!entry.getValue().equalsIgnoreCase(sha256(file))) {
                throw new IOException("Pi runtime file hash mismatch: " + entry.getKey());
            }
        }
    }

    private boolean containsSymbolicLink(Path directory, Path relative) {
        Path current = directory;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void requireEqual(String expected, String actual, String name) throws IOException {
        if (!expected.equals(actual)) {
            throw new IOException("Pi runtime manifest " + name + " does not match");
        }
    }

    private AgentRuntimeEnvironmentReport report(
            AgentRuntimeEnvironmentStatus status,
            String os,
            String architecture,
            List<String> checks,
            Map<String, String> diagnostics) {
        return new AgentRuntimeEnvironmentReport(
                AgentRuntimeType.PI, status, layout.version(), os, architecture,
                checks, diagnostics, LocalDateTime.now(clock));
    }
}
