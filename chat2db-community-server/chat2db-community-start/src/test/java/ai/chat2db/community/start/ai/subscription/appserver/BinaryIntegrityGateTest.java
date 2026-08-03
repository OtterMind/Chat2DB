package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.internal.BinaryIntegrityGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class BinaryIntegrityGateTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsMatchingChecksumWithoutTrustingInjectedVersion() throws Exception {
        Path binary = tempDir.resolve("app-server");
        Files.writeString(binary, "fake-binary-bytes");
        String sha = BinaryIntegrityGate.sha256Hex(binary);
        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "1.2.3", sha, "chat2db-pinned-v1");
        // Binary gate no longer consults an injected observed version.
        new BinaryIntegrityGate().verifyBinary(spec);
    }

    @Test
    void rejectsChecksumMismatch() throws Exception {
        Path binary = tempDir.resolve("app-server");
        Files.writeString(binary, "fake-binary-bytes");
        String wrong = "0".repeat(64);
        AppServerBinarySpec spec = new AppServerBinarySpec(binary, "1.2.3", wrong, "chat2db-pinned-v1");
        AppServerException ex = assertThrows(AppServerException.class,
                () -> new BinaryIntegrityGate().verifyBinary(spec));
        assertEquals(AppServerDisabledReason.BINARY_CHECKSUM_MISMATCH, ex.reason());
    }

    @Test
    void rejectsMissingBinary() {
        Path missing = tempDir.resolve("missing");
        AppServerBinarySpec spec = new AppServerBinarySpec(missing, "1.0.0", "a".repeat(64), "chat2db-pinned-v1");
        AppServerException ex = assertThrows(AppServerException.class,
                () -> new BinaryIntegrityGate().verifyBinary(spec));
        assertEquals(AppServerDisabledReason.BINARY_MISSING, ex.reason());
    }

    @Test
    void extractsSemanticVersionFromUserAgent() {
        assertEquals("0.145.0",
                BinaryIntegrityGate.extractSemanticVersion("codex_app_server/0.145.0"));
        assertEquals("1.2.3-beta.1",
                BinaryIntegrityGate.extractSemanticVersion("codex_app_server/1.2.3-beta.1 (unix)"));
        assertNull(BinaryIntegrityGate.extractSemanticVersion("codex without version"));
        assertNull(BinaryIntegrityGate.extractSemanticVersion(null));
    }

    @Test
    void verifyVersionFromUserAgentFailsClosedOnMismatchOrMissing() {
        BinaryIntegrityGate gate = new BinaryIntegrityGate();
        gate.verifyVersionFromUserAgent("codex_app_server/1.2.3", "1.2.3");

        AppServerException mismatch = assertThrows(AppServerException.class,
                () -> gate.verifyVersionFromUserAgent("codex_app_server/9.9.9", "1.2.3"));
        assertEquals(AppServerDisabledReason.BINARY_VERSION_MISMATCH, mismatch.reason());

        AppServerException missing = assertThrows(AppServerException.class,
                () -> gate.verifyVersionFromUserAgent("no-version-here", "1.2.3"));
        assertEquals(AppServerDisabledReason.BINARY_VERSION_MISMATCH, missing.reason());

        AppServerException absent = assertThrows(AppServerException.class,
                () -> gate.verifyVersionFromUserAgent(null, "1.2.3"));
        assertEquals(AppServerDisabledReason.BINARY_VERSION_MISMATCH, absent.reason());
    }
}
