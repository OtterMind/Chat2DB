package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

public class PiRuntimeInstaller implements PiRuntimeInstallation {

    private static final long MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;

    private final PiRuntimePaths paths;
    private final String version;
    private final URI sourceRoot;
    private final ResourceFetcher fetcher;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PiRuntimeInstaller(PiRuntimePaths paths, String version, URI sourceRoot) {
        this(paths, version, sourceRoot, new HttpResourceFetcher(HttpClient.newHttpClient()));
    }

    PiRuntimeInstaller(PiRuntimePaths paths, String version, URI sourceRoot, ResourceFetcher fetcher) {
        if (!"https".equalsIgnoreCase(sourceRoot.getScheme())) {
            throw new IllegalArgumentException("Pi runtime source must use HTTPS");
        }
        this.paths = paths;
        this.version = version;
        this.sourceRoot = sourceRoot.toString().endsWith("/") ? sourceRoot : URI.create(sourceRoot + "/");
        this.fetcher = fetcher;
    }

    @Override
    public synchronized Path install(AgentRuntimeEnvironmentRequest environment) throws IOException {
        String os = PiRuntimeLayout.normalizeOperatingSystem(environment.operatingSystem());
        String architecture = PiRuntimeLayout.normalizeArchitecture(environment.architecture());
        String platform = os + "-" + architecture;
        PiRuntimeLayout finalLayout = new PiRuntimeLayout(paths.installations(), version);
        Path target = finalLayout.platformDirectory(os, architecture);
        AgentRuntimeEnvironmentReport existing = new PiRuntimeEnvironmentChecker(finalLayout).inspect(environment);
        if (existing.status() == AgentRuntimeEnvironmentStatus.READY) {
            return target;
        }

        Path stagingRoot = paths.temporary().resolve(UUID.randomUUID().toString());
        Path staging = stagingRoot.resolve(version).resolve(platform).normalize();
        if (!staging.startsWith(paths.temporary())) {
            throw new IOException("Pi runtime staging path is unsafe");
        }
        try {
            Files.createDirectories(staging);
            URI platformRoot = sourceRoot.resolve(version + "/" + platform + "/");
            byte[] manifestBytes = fetcher.fetch(platformRoot.resolve("runtime-manifest.json"), MAX_MANIFEST_BYTES);
            PiRuntimeManifest manifest = objectMapper.readValue(manifestBytes, PiRuntimeManifest.class);
            write(staging.resolve("runtime-manifest.json"), manifestBytes);
            validateIdentity(manifest, os, architecture);
            for (String relativeName : manifest.files().keySet()) {
                Path relative = Path.of(relativeName).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) {
                    throw new IOException("Pi runtime manifest contains an unsafe file path");
                }
                write(staging.resolve(relative), fetcher.fetch(platformRoot.resolve(relativeName), MAX_FILE_BYTES));
            }
            Path executable = staging.resolve("windows".equals(os) ? "pi.exe" : "pi");
            if (!"windows".equals(os) && !executable.toFile().setExecutable(true, true)) {
                throw new IOException("Cannot make Pi runtime executable");
            }
            PiRuntimeLayout stagingLayout = new PiRuntimeLayout(stagingRoot, version);
            AgentRuntimeEnvironmentReport report = new PiRuntimeEnvironmentChecker(stagingLayout).inspect(
                    new AgentRuntimeEnvironmentRequest(
                            environment.applicationVersion(), os, architecture));
            if (report.status() != AgentRuntimeEnvironmentStatus.READY) {
                throw new IOException("Downloaded Pi runtime failed verification: "
                        + report.diagnostics().getOrDefault("reason", "unknown reason"));
            }
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                deleteTree(target);
            }
            move(staging, target);
            deleteTree(stagingRoot);
            return target;
        } catch (IOException | RuntimeException error) {
            deleteTree(stagingRoot);
            throw error;
        }
    }

    private void validateIdentity(PiRuntimeManifest manifest, String os, String architecture) throws IOException {
        if (!version.equals(manifest.version())
                || !os.equals(manifest.operatingSystem())
                || !architecture.equals(manifest.architecture())) {
            throw new IOException("Pi runtime manifest identity does not match the requested runtime");
        }
    }

    private void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target);
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
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
                        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
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
}
