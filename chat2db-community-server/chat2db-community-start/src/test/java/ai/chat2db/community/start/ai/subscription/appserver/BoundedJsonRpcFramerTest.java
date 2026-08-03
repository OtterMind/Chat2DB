package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.internal.BoundedJsonRpcFramer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class BoundedJsonRpcFramerTest {

    @Test
    void readsAndWritesNewlineDelimitedFrames() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream("{\"id\":1}\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(in, out, 1024)) {
            assertEquals("{\"id\":1}", framer.readMessage());
            framer.writeMessage("{\"method\":\"initialized\"}");
        }
        assertEquals("{\"method\":\"initialized\"}\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOversizedIncomingFrameWithoutMaterializingWholePayload() {
        // Bound is 64 bytes; payload is far larger. Failure must happen during bounded read.
        String huge = "x".repeat(200);
        ByteArrayInputStream in = new ByteArrayInputStream((huge + "\n{\"id\":2}\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AppServerException ex = assertThrows(AppServerException.class, () -> {
            try (BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(in, out, 64)) {
                framer.readMessage();
            }
        });
        assertEquals(AppServerDisabledReason.OVERSIZED_MESSAGE, ex.reason());
    }

    @Test
    void rejectsOutgoingFrameWithEmbeddedNewline() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AppServerException ex = assertThrows(AppServerException.class, () -> {
            try (BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(in, out, 1024)) {
                framer.writeMessage("{\"a\":1}\n{\"b\":2}");
            }
        });
        assertEquals(AppServerDisabledReason.MALFORMED_MESSAGE, ex.reason());
    }

    @Test
    void serializesConcurrentWritesWithoutInterleaving() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(in, out, 1024)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<Exception> failure = new AtomicReference<>();
            Runnable writer = () -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 50; i++) {
                        framer.writeMessage("{\"n\":" + i + "}");
                    }
                } catch (Exception ex) {
                    failure.set(ex);
                } finally {
                    done.countDown();
                }
            };
            Thread t1 = new Thread(writer, "write-a");
            Thread t2 = new Thread(writer, "write-b");
            t1.start();
            t2.start();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw failure.get();
            }
        }
        String written = out.toString(StandardCharsets.UTF_8);
        String[] lines = written.split("\n", -1);
        // 100 messages + trailing empty after final newline
        int nonEmpty = 0;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            nonEmpty++;
            assertTrue(line.startsWith("{\"n\":"), "interleaved frame: " + line);
            assertTrue(line.endsWith("}"));
        }
        assertEquals(100, nonEmpty);
    }
}
