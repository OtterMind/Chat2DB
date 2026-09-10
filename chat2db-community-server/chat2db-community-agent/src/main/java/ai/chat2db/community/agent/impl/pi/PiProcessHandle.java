package ai.chat2db.community.agent.impl.pi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PiProcessHandle implements AutoCloseable {

    private static final int STDERR_TAIL_BYTES = 64 * 1024;

    private final String sessionId;
    private final Process process;
    private final ByteArrayOutputStream stderrTail = new ByteArrayOutputStream(STDERR_TAIL_BYTES);
    private final Thread stderrReader;

    public PiProcessHandle(String sessionId, Process process) {
        this.sessionId = sessionId;
        this.process = process;
        this.stderrReader = new Thread(this::drainStderr, "chat2db-pi-stderr-" + sessionId);
        this.stderrReader.setDaemon(true);
        this.stderrReader.start();
    }

    public String sessionId() {
        return sessionId;
    }

    public Process process() {
        return process;
    }

    public InputStream stdout() {
        return process.getInputStream();
    }

    public OutputStream stdin() {
        return process.getOutputStream();
    }

    public synchronized String stderrTail() {
        return stderrTail.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            stderrReader.interrupt();
        }
    }

    private void drainStderr() {
        byte[] buffer = new byte[4096];
        try (InputStream input = process.getErrorStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                appendTail(buffer, read);
            }
        } catch (IOException ignored) {
            // Process exit closes stderr; the retained tail remains available for diagnostics.
        }
    }

    private synchronized void appendTail(byte[] bytes, int length) {
        byte[] current = stderrTail.toByteArray();
        int keep = Math.min(current.length, STDERR_TAIL_BYTES - Math.min(length, STDERR_TAIL_BYTES));
        stderrTail.reset();
        if (keep > 0) {
            stderrTail.write(current, current.length - keep, keep);
        }
        int write = Math.min(length, STDERR_TAIL_BYTES);
        stderrTail.write(bytes, length - write, write);
    }
}
