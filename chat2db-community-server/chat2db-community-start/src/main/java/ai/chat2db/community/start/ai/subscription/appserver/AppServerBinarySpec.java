package ai.chat2db.community.start.ai.subscription.appserver;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Release-pinned app-server binary identity. Checksums and versions are packaging inputs;
 * runtime never downloads binaries.
 */
public final class AppServerBinarySpec {

    private final Path binaryPath;
    private final String expectedVersion;
    private final String expectedSha256Hex;
    private final String requiredProtocolLabel;

    public AppServerBinarySpec(
            Path binaryPath,
            String expectedVersion,
            String expectedSha256Hex,
            String requiredProtocolLabel) {
        this.binaryPath = Objects.requireNonNull(binaryPath, "binaryPath");
        this.expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion");
        this.expectedSha256Hex = normalizeSha(expectedSha256Hex);
        this.requiredProtocolLabel = Objects.requireNonNull(requiredProtocolLabel, "requiredProtocolLabel");
    }

    public Path binaryPath() {
        return binaryPath;
    }

    public String expectedVersion() {
        return expectedVersion;
    }

    public String expectedSha256Hex() {
        return expectedSha256Hex;
    }

    public String requiredProtocolLabel() {
        return requiredProtocolLabel;
    }

    private static String normalizeSha(String sha) {
        Objects.requireNonNull(sha, "expectedSha256Hex");
        String trimmed = sha.trim().toLowerCase();
        if (!trimmed.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedSha256Hex must be 64 hex characters");
        }
        return trimmed;
    }
}
