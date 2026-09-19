package ai.chat2db.community.updater.v2.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateHelperAckTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingLogIsNotAnAcknowledgement() {
        assertFalse(UpdateHelperAck.acknowledged(temporaryDirectory.resolve("absent.log")));
        assertFalse(UpdateHelperAck.acknowledged(null));
    }

    @Test
    void onlyHelperLinesCountAsAcknowledgement() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx.log");
        Files.writeString(log, "actor=APPLICATION stage=HANDOFF event=STARTED outcome=PERSISTED\n");
        assertFalse(UpdateHelperAck.acknowledged(log),
            "the application's own handoff line must not pass as a helper acknowledgement");

        Files.writeString(log, "actor=HELPER stage=HANDOFF event=ACK outcome=PERSISTED\n",
            StandardOpenOption.APPEND);

        assertTrue(UpdateHelperAck.acknowledged(log));
    }

    @Test
    void waitsUntilTheHelperWritesItsFirstLine() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx-wait.log");
        Files.writeString(log, "actor=APPLICATION\n");
        Thread helper = new Thread(() -> {
            try {
                Thread.sleep(300L);
                Files.writeString(log, "actor=HELPER stage=HANDOFF event=ACK\n", StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // The assertion below reports the missing acknowledgement.
            }
        });
        helper.start();

        assertTrue(UpdateHelperAck.await(log, Duration.ofSeconds(10L)));
        helper.join();
    }

    @Test
    void reportsAMissingAcknowledgementAfterTheTimeout() throws Exception {
        Path log = temporaryDirectory.resolve("update-tx-timeout.log");
        Files.writeString(log, "actor=APPLICATION\n");

        long startedAt = System.nanoTime();
        boolean acknowledged = UpdateHelperAck.await(log, Duration.ofMillis(750L));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertFalse(acknowledged);
        assertTrue(elapsedMillis >= 500L, "the handoff must actually wait before giving up: " + elapsedMillis);
    }

    @Test
    void anUnreadableLogIsNotAnAcknowledgement() throws Exception {
        Path directoryInsteadOfFile = Files.createDirectory(temporaryDirectory.resolve("update-tx-dir.log"));
        assertFalse(UpdateHelperAck.acknowledged(directoryInsteadOfFile));
    }
}
