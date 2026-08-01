package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.start.ai.subscription.appserver.KeyringAvailabilityProbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Conservative platform preflight; the app-server remains the final keyring authority. */
public final class SystemKeyringAvailabilityProbe implements KeyringAvailabilityProbe {

    private static final Path MAC_SECURITY = Path.of("/usr/bin/security");
    private static final long MAC_PROBE_TIMEOUT_SECONDS = 2L;

    private final String osName;
    private final String systemRoot;
    private final String dbusSessionAddress;
    private final BooleanSupplier macDefaultKeychainProbe;

    public SystemKeyringAvailabilityProbe() {
        this(
                System.getProperty("os.name", ""),
                System.getenv("SystemRoot"),
                System.getenv("DBUS_SESSION_BUS_ADDRESS"),
                SystemKeyringAvailabilityProbe::canResolveMacDefaultUserKeychain);
    }

    SystemKeyringAvailabilityProbe(
            String osName,
            String systemRoot,
            String dbusSessionAddress,
            BooleanSupplier macDefaultKeychainProbe) {
        this.osName = osName == null ? "" : osName;
        this.systemRoot = systemRoot;
        this.dbusSessionAddress = dbusSessionAddress;
        this.macDefaultKeychainProbe = macDefaultKeychainProbe;
    }

    @Override
    public boolean isKeyringAvailable() {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            try {
                return macDefaultKeychainProbe.getAsBoolean();
            } catch (RuntimeException ex) {
                return false;
            }
        }
        if (os.contains("win")) {
            return systemRoot != null && Files.isRegularFile(Path.of(systemRoot, "System32", "cmdkey.exe"));
        }
        if (os.contains("linux")) {
            return dbusSessionAddress != null && !dbusSessionAddress.isBlank();
        }
        return false;
    }

    private static boolean canResolveMacDefaultUserKeychain() {
        if (!Files.isExecutable(MAC_SECURITY)) {
            return false;
        }
        Process process = null;
        try {
            process = new ProcessBuilder(
                    MAC_SECURITY.toString(), "default-keychain", "-d", "user")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(MAC_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
