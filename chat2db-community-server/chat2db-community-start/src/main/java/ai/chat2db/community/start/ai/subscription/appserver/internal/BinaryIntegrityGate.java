package ai.chat2db.community.start.ai.subscription.appserver.internal;

import ai.chat2db.community.start.ai.subscription.appserver.AppServerBinarySpec;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerDisabledReason;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies the packaged app-server binary exists and matches the pinned SHA-256.
 * Semantic version is proven separately from the official initialize {@code userAgent}
 * and must never trust a caller-injected observed version string.
 */
public final class BinaryIntegrityGate {

    private static final Pattern SEMVER = Pattern.compile(
            "(\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.]+)?)");

    /**
     * Verifies binary path and SHA-256 only.
     */
    public void verifyBinary(AppServerBinarySpec spec) {
        Objects.requireNonNull(spec, "spec");
        Path path = spec.binaryPath();
        if (!Files.isRegularFile(path)) {
            throw new AppServerException(
                    AppServerDisabledReason.BINARY_MISSING,
                    "pinned app-server binary is missing");
        }
        String actualSha;
        try {
            actualSha = sha256Hex(path);
        } catch (IOException ex) {
            throw new AppServerException(
                    AppServerDisabledReason.BINARY_CHECKSUM_MISMATCH,
                    "unable to hash pinned app-server binary",
                    ex);
        }
        if (!actualSha.equals(spec.expectedSha256Hex())) {
            throw new AppServerException(
                    AppServerDisabledReason.BINARY_CHECKSUM_MISMATCH,
                    "pinned app-server binary SHA-256 mismatch");
        }
    }

    /**
     * Extracts a semantic version from initialize {@code userAgent} and compares to the pin.
     * Missing userAgent or version fails closed.
     */
    public void verifyVersionFromUserAgent(String userAgent, String expectedVersion) {
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        String expected = expectedVersion.trim();
        if (expected.isEmpty()) {
            throw new AppServerException(
                    AppServerDisabledReason.BINARY_VERSION_MISMATCH,
                    "expected binary version pin is blank");
        }
        String extracted = extractSemanticVersion(userAgent);
        if (extracted == null) {
            throw new AppServerException(
                    AppServerDisabledReason.BINARY_VERSION_MISMATCH,
                    "initialize userAgent missing semantic version");
        }
        if (!extracted.equals(expected)) {
            throw new AppServerException(
                    AppServerDisabledReason.BINARY_VERSION_MISMATCH,
                    "pinned app-server version mismatch");
        }
    }

    public static String extractSemanticVersion(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        Matcher matcher = SEMVER.matcher(userAgent);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    /**
     * @deprecated Prefer {@link #verifyBinary(AppServerBinarySpec)} plus
     * {@link #verifyVersionFromUserAgent(String, String)}. The observedVersion argument is untrusted.
     */
    @Deprecated
    public void verify(AppServerBinarySpec spec, String observedVersion) {
        verifyBinary(spec);
        // Intentionally ignore injected observedVersion — it is not an integrity signal.
        if (observedVersion != null && !observedVersion.isBlank()
                && !observedVersion.trim().equals(spec.expectedVersion())) {
            // Keep legacy callers that still pass a mismatched injection from silently continuing
            // only after initialize; binary check alone is enough here.
        }
    }

    public static String sha256Hex(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        try (InputStream in = Files.newInputStream(path);
             DigestInputStream din = new DigestInputStream(in, digest)) {
            byte[] buf = new byte[8192];
            while (din.read(buf) != -1) {
                // drain
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }
}
