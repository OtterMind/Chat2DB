package ai.chat2db.community.jcef.agent;

import java.nio.file.Path;
import java.util.Locale;

public class PiRuntimeLayout {

    private final Path runtimeRoot;
    private final String version;

    public PiRuntimeLayout(Path runtimeRoot, String version) {
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.version = requireText(version, "version");
    }

    public Path platformDirectory(String operatingSystem, String architecture) {
        return runtimeRoot.resolve(version)
                .resolve(normalizeOperatingSystem(operatingSystem) + "-" + normalizeArchitecture(architecture))
                .normalize();
    }

    public Path executable(String operatingSystem, String architecture) {
        return platformDirectory(operatingSystem, architecture)
                .resolve("windows".equals(normalizeOperatingSystem(operatingSystem)) ? "pi.exe" : "pi");
    }

    public String version() {
        return version;
    }

    static String normalizeOperatingSystem(String value) {
        String os = requireText(value, "operatingSystem").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os;
    }

    static String normalizeArchitecture(String value) {
        return switch (requireText(value, "architecture").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
