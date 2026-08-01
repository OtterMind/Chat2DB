package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.internal.StderrDrain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class StderrDrainTest {

    @Test
    void drainsRedactsAndDoesNotRetainCredentialBodies() throws Exception {
        String payload = "Authorization: Bearer super-secret-token\nplain status line\n";
        List<String> captured = new ArrayList<>();
        try (StderrDrain drain = new StderrDrain(
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)),
                4096,
                256,
                captured::add)) {
            // Wait briefly for daemon drain thread to consume the fixed stream.
            long deadline = System.currentTimeMillis() + 3_000L;
            while (drain.linesDrained() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertTrue(drain.linesDrained() >= 2);
        }
        assertFalse(captured.isEmpty());
        String joined = String.join("\n", captured);
        assertFalse(joined.contains("super-secret-token"));
        assertTrue(joined.contains(SensitivePayloadRedactor.REDACTED) || joined.contains("plain status"));
    }
}
