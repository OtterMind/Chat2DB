package ai.chat2db.community.start.ai.subscription.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Validated metadata for the app-server binary staged by Community packaging. */
public record SubscriptionRuntimeManifest(
        Path manifestPath,
        Path binaryPath,
        String version,
        String protocolLabel,
        String binarySha256) {

    /**
     * The packaged asset is the standalone app-server binary, not the full {@code codex} CLI.
     * <p>
     * CLI {@code -c} overrides force CodeMode off, prefer non-deferred MCP, and allow loopback
     * network under workspace-write so HTTP {@code tools/call} can complete.
     */
    public List<String> stdioLaunchCommand() {
        // Keep args shell-free: ProcessBuilder passes each element as one argv token.
        return List.of(
                binaryPath.toString(),
                "-c", "features.code_mode=false",
                "-c", "features.code_mode_host=false",
                "-c", "features.code_mode_only=false",
                "-c", "features.tool_search=false",
                "-c", "features.tool_search_always_defer_mcp_tools=false",
                "-c", "features.search_tool=false",
                "-c", "features.request_permissions=false",
                "-c", "sandbox_mode=\"workspace-write\"",
                "-c", "sandbox_workspace_write.network_access=true",
                "--listen", "stdio://");
    }

    public static SubscriptionRuntimeManifest load(String configuredPath) {
        Path manifest = resolveManifest(configuredPath);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(manifest)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read subscription runtime manifest", exception);
        }
        require("1", properties.getProperty("schemaVersion"), "schemaVersion");
        require("OPENAI", properties.getProperty("provider"), "provider");
        require("SUBSCRIPTION", properties.getProperty("accessType"), "accessType");
        String binary = requireText(properties, "binary");
        String version = requireText(properties, "version");
        String protocol = requireText(properties, "protocolLabel");
        String sha = requireText(properties, "binarySha256").toLowerCase();
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid binarySha256 in subscription runtime manifest");
        }
        Path binaryPath = manifest.getParent().resolve(binary).normalize();
        if (!binaryPath.startsWith(manifest.getParent()) || !Files.isRegularFile(binaryPath)) {
            throw new IllegalStateException("Subscription app-server binary is missing or escapes its package");
        }
        return new SubscriptionRuntimeManifest(manifest, binaryPath, version, protocol, sha);
    }

    static Path resolveManifest(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("Subscription runtime manifest path is not configured");
        }
        Path configured = Path.of(configuredPath);
        List<Path> candidates = new ArrayList<>();
        candidates.add(configured);
        if (!configured.isAbsolute()) {
            candidates.add(Path.of(System.getProperty("user.dir", ".")).resolve(configured));
            String packagedAppPath = System.getProperty("jpackage.app-path");
            if (isPackagedMacLauncher(packagedAppPath)) {
                candidates.add(resolvePackagedMacManifest(configured, packagedAppPath));
            }
            try {
                Path codeSource = Path.of(SubscriptionRuntimeManifest.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                Path jarDirectory = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
                if (jarDirectory != null) {
                    candidates.add(jarDirectory.resolve(configured));
                }
            } catch (URISyntaxException | RuntimeException ignored) {
                // Other candidates still fail closed below.
            }
        }
        return candidates.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Subscription runtime manifest was not found"));
    }

    static Path resolvePackagedMacManifest(Path configured, String packagedAppPath) {
        if (configured.isAbsolute()) {
            throw new IllegalStateException("Packaged subscription manifest must be relative");
        }
        Path launcher = Path.of(packagedAppPath).toAbsolutePath().normalize();
        Path macOsDirectory = launcher.getParent();
        Path contentsDirectory = macOsDirectory == null ? null : macOsDirectory.getParent();
        if (macOsDirectory == null || contentsDirectory == null
                || !"MacOS".equals(fileName(macOsDirectory))
                || !"Contents".equals(fileName(contentsDirectory))) {
            throw new IllegalStateException("Unsupported packaged macOS launcher layout");
        }
        Path appPayloadDirectory = contentsDirectory.resolve("app").normalize();
        Path candidate = appPayloadDirectory.resolve(configured).normalize();
        if (!candidate.startsWith(appPayloadDirectory)) {
            throw new IllegalStateException("Packaged subscription manifest escapes Contents/app");
        }
        return candidate;
    }

    private static boolean isPackagedMacLauncher(String packagedAppPath) {
        if (packagedAppPath == null || packagedAppPath.isBlank()) {
            return false;
        }
        try {
            Path launcher = Path.of(packagedAppPath).toAbsolutePath().normalize();
            Path macOsDirectory = launcher.getParent();
            Path contentsDirectory = macOsDirectory == null ? null : macOsDirectory.getParent();
            return macOsDirectory != null && contentsDirectory != null
                    && "MacOS".equals(fileName(macOsDirectory))
                    && "Contents".equals(fileName(contentsDirectory));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static String requireText(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + key + " in subscription runtime manifest");
        }
        return value.trim();
    }

    private static void require(String expected, String actual, String key) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("Unsupported " + key + " in subscription runtime manifest");
        }
    }
}
