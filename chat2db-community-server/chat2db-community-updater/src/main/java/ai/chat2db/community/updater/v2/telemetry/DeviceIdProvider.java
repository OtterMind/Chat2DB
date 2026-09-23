package ai.chat2db.community.updater.v2.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the stable desktop device id.
 *
 * <p>Preference order is the OS machine identifier, which gives the same value for Community, Pro and
 * Local on one machine. When no machine identifier can be read, a random id is generated once and
 * persisted by {@link TelemetryStore}.</p>
 */
public final class DeviceIdProvider {

    private static final String MACHINE_PREFIX = "d1_";

    private static final String RANDOM_PREFIX = "r1_";

    private static final String SALT = "chat2db-device-v1\n";

    private static final Pattern PLATFORM_UUID = Pattern.compile("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"");

    private static final Pattern MACHINE_GUID = Pattern.compile("([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");

    private static final long COMMAND_TIMEOUT_SECONDS = 2L;

    private DeviceIdProvider() {
    }

    public static String resolve() {
        String machineId = readMachineId();
        if (machineId == null || machineId.isBlank()) {
            return randomId();
        }
        return derive(machineId);
    }

    /** One machine, one device id: Community, Pro and Local on the same machine report the same value. */
    static String derive(String machineId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = (SALT + machineId.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
            return MACHINE_PREFIX + HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            return randomId();
        }
    }

    static String randomId() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return RANDOM_PREFIX + HexFormat.of().formatHex(value);
    }

    private static String readMachineId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return match(PLATFORM_UUID, run(List.of("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")));
        }
        if (os.contains("win")) {
            return match(MACHINE_GUID, run(List.of("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v",
                "MachineGuid")));
        }
        return firstExisting(Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id"));
    }

    private static String match(Pattern pattern, String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return output;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static String firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            try {
                if (Files.isReadable(candidate)) {
                    String value = Files.readString(candidate, StandardCharsets.UTF_8);
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            } catch (IOException ignored) {
                // Fall through to the next candidate.
            }
        }
        return null;
    }
}
