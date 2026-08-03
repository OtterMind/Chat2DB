package ai.chat2db.community.start.ai.subscription.appserver.internal;

import ai.chat2db.community.start.ai.subscription.appserver.SensitivePayloadRedactor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Drains app-server stderr so the OS pipe cannot fill and deadlock the child process.
 * Lines are size-bounded, redacted, and never retained as full credential-bearing bodies.
 */
public final class StderrDrain implements AutoCloseable {

    public static final int DEFAULT_MAX_LINE_BYTES = 4_096;
    public static final int DEFAULT_MAX_REDACTED_CHARS = 256;

    private final InputStream stderr;
    private final int maxLineBytes;
    private final int maxRedactedChars;
    private final Consumer<String> redactedLineSink;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong linesDrained = new AtomicLong();
    private final AtomicLong bytesDiscarded = new AtomicLong();
    private final Thread drainThread;

    public StderrDrain(InputStream stderr) {
        this(stderr, DEFAULT_MAX_LINE_BYTES, DEFAULT_MAX_REDACTED_CHARS, line -> {
            // Default: count only; do not log credential-bearing bodies.
        });
    }

    public StderrDrain(
            InputStream stderr,
            int maxLineBytes,
            int maxRedactedChars,
            Consumer<String> redactedLineSink) {
        this.stderr = stderr;
        this.maxLineBytes = maxLineBytes;
        this.maxRedactedChars = maxRedactedChars;
        this.redactedLineSink = redactedLineSink == null ? line -> {
        } : redactedLineSink;
        this.drainThread = new Thread(this::run, "codex-app-server-stderr-drain");
        this.drainThread.setDaemon(true);
        this.drainThread.start();
    }

    public long linesDrained() {
        return linesDrained.get();
    }

    public long bytesDiscarded() {
        return bytesDiscarded.get();
    }

    private void run() {
        byte[] buf = new byte[maxLineBytes];
        int pos = 0;
        try {
            while (!closed.get()) {
                int b = stderr.read();
                if (b == -1) {
                    return;
                }
                if (b == '\n') {
                    emit(buf, pos);
                    pos = 0;
                    continue;
                }
                if (pos < maxLineBytes) {
                    buf[pos++] = (byte) b;
                } else {
                    bytesDiscarded.incrementAndGet();
                }
            }
        } catch (IOException ignored) {
            // peer closed or supervisor shutdown
        }
    }

    private void emit(byte[] buf, int len) {
        if (len <= 0) {
            return;
        }
        linesDrained.incrementAndGet();
        String raw = new String(buf, 0, len, StandardCharsets.UTF_8);
        String redacted = SensitivePayloadRedactor.redactText(raw);
        if (redacted.length() > maxRedactedChars) {
            redacted = redacted.substring(0, maxRedactedChars);
        }
        try {
            redactedLineSink.accept(redacted);
        } catch (RuntimeException ignored) {
            // sink faults must not stop draining
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            stderr.close();
        } catch (IOException ignored) {
        }
        drainThread.interrupt();
    }
}
