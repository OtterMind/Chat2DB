package ai.chat2db.community.updater.v2.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateHelperAckTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingLogIsNotAnAcknowledgement() {
        Path missing = temporaryDirectory.resolve("absent.log");
        assertEquals(0L, UpdateHelperAck.offset(missing));
        assertFalse(UpdateHelperAck.acknowledgedSince(missing, 0L));
    }

    @Test
    void offsetFollowsTheLogLength() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx.log");
        Files.writeString(log, "actor=APPLICATION stage=HANDOFF event=STARTED outcome=PERSISTED\n");
        long offset = UpdateHelperAck.offset(log);

        assertEquals(Files.size(log), offset);
        assertFalse(UpdateHelperAck.acknowledgedSince(log, offset),
            "the application's own handoff line must not pass as a helper acknowledgement");
    }

    @Test
    void helperLineAppendedAfterTheOffsetIsAnAcknowledgement() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx-append.log");
        Files.writeString(log, "actor=APPLICATION stage=HANDOFF event=STARTED outcome=PERSISTED\n");
        long offset = UpdateHelperAck.offset(log);

        Files.writeString(log, "actor=HELPER stage=HANDOFF event=ACK outcome=PERSISTED\n",
            StandardOpenOption.APPEND);

        assertTrue(UpdateHelperAck.acknowledgedSince(log, offset));
    }

    @Test
    void anEarlierAttemptsAcknowledgementDoesNotCountAgain() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx-retry.log");
        Files.writeString(log, "actor=HELPER stage=HANDOFF event=ACK outcome=PERSISTED\n");
        long offset = UpdateHelperAck.offset(log);

        assertFalse(UpdateHelperAck.acknowledgedSince(log, offset),
            "a retry must wait for a new helper instead of trusting the previous attempt");
    }

    @Test
    void waitsUntilTheHelperAppendsItsFirstLine() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx-wait.log");
        Files.writeString(log, "actor=APPLICATION\n");
        long offset = UpdateHelperAck.offset(log);
        Thread helper = new Thread(() -> {
            try {
                Thread.sleep(300L);
                Files.writeString(log, "actor=HELPER stage=HANDOFF event=ACK\n", StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // The assertion below reports the missing acknowledgement.
            }
        });
        helper.start();

        assertTrue(UpdateHelperAck.awaitSince(log, offset, Duration.ofSeconds(10L)));
        helper.join();
    }

    @Test
    void reportsAMissingAcknowledgementAfterTheTimeout() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx-timeout.log");
        Files.writeString(log, "actor=APPLICATION\n");
        long offset = UpdateHelperAck.offset(log);

        long startedAt = System.nanoTime();
        boolean acknowledged = UpdateHelperAck.awaitSince(log, offset, Duration.ofMillis(750L));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertFalse(acknowledged);
        assertTrue(elapsedMillis >= 500L, "the handoff must actually wait before giving up: " + elapsedMillis);
    }

    @Test
    void aPathThatCannotBeReadIsNotAnAcknowledgement() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("update-tx-dir.log"));
        assertFalse(UpdateHelperAck.acknowledgedSince(directory, 0L));
    }
}
