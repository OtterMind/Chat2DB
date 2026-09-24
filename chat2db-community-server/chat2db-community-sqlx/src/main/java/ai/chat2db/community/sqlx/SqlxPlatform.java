package ai.chat2db.community.sqlx;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

/**
 * The SQLX release asset and install location for one operating system and architecture.
 * <p>
 * The rules mirror the official installers: {@code sqlx-<os>-<arch>.zip} from GitHub Releases,
 * installed into the user-level directory those installers use so AI agents find the executable
 * without extra configuration.
 */
public final class SqlxPlatform {

    private final String os;
    private final String arch;

    private SqlxPlatform(String os, String arch) {
        this.os = os;
        this.arch = arch;
    }

    /** Detect the current platform, or empty when SQLX publishes no archive for it. */
    public static Optional<SqlxPlatform> current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Best-effort platform name for the renderer, even when the architecture is unsupported. */
    public static String rendererPlatform() {
        String os = os(System.getProperty("os.name", ""));
        if ("macos".equals(os)) {
            return "mac";
        }
        if ("windows".equals(os)) {
            return "windows";
        }
        return "linux";
    }

    /** User-level directory the official installers use on this machine. */
    public static Path defaultInstallDirectory() {
        if ("windows".equals(os(System.getProperty("os.name", "")))) {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path base = localAppData == null || localAppData.isBlank()
                    ? Paths.get(System.getProperty("user.home", "."), "AppData", "Local")
                    : Paths.get(localAppData);
            return base.resolve("Programs").resolve("SQLX");
        }
        return Paths.get(System.getProperty("user.home", "."), ".local", "bin");
    }

    /** Executable name on this machine, used before the architecture is known. */
    public static String defaultExecutableName() {
        return "windows".equals(os(System.getProperty("os.name", ""))) ? "sqlx.exe" : "sqlx";
    }

    static Optional<SqlxPlatform> of(String osName, String architecture) {
        String os = os(osName);
        String arch = arch(architecture);
        if (os == null || arch == null) {
            return Optional.empty();
        }
        // The release publishes macOS arm64/x64, Linux arm64/x64 and Windows x64 only.
        if ("windows".equals(os) && !"x64".equals(arch)) {
            return Optional.empty();
        }
        return Optional.of(new SqlxPlatform(os, arch));
    }

    private static String os(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return "macos";
        }
        if (normalized.contains("win")) {
            return "windows";
        }
        if (normalized.contains("linux")) {
            return "linux";
        }
        return null;
    }

    private static String arch(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("aarch64") || normalized.contains("arm64")) {
            return "arm64";
        }
        if (normalized.contains("x86_64") || normalized.contains("amd64") || normalized.contains("x64")) {
            return "x64";
        }
        return null;
    }

    /** Value the renderer expects for the platform switch. */
    public String rendererName() {
        return switch (os) {
            case "macos" -> "mac";
            case "windows" -> "windows";
            default -> "linux";
        };
    }

    public String assetName() {
        return "sqlx-" + os + "-" + arch + ".zip";
    }

    public String executableName() {
        return "windows".equals(os) ? "sqlx.exe" : "sqlx";
    }

    /** User-level directory the official installers use. */
    public Path installDirectory() {
        return defaultInstallDirectory();
    }

    public Path executable() {
        return installDirectory().resolve(executableName());
    }

    String os() {
        return os;
    }

    String arch() {
        return arch;
    }
}
