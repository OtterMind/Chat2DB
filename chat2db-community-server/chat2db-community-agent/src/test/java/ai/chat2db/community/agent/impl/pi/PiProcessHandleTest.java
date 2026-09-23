package ai.chat2db.community.agent.impl.pi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProcessHandleTest {

    @Test
    void drainsBoundedStderrAndStopsTheProcess() throws Exception {
        byte[] stderr = ("x".repeat(70 * 1024) + "tail-marker").getBytes(StandardCharsets.UTF_8);
        FakeProcess process = new FakeProcess(stderr);
        PiProcessHandle handle = new PiProcessHandle("session", process);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!handle.stderrTail().endsWith("tail-marker") && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertTrue(handle.stderrTail().endsWith("tail-marker"));
        assertTrue(handle.stderrTail().length() <= 64 * 1024);
        handle.close();
        assertFalse(process.isAlive());
    }

    private static final class FakeProcess extends Process {
        private final InputStream stderr;
        private boolean alive = true;
        private FakeProcess(byte[] stderr) { this.stderr = new ByteArrayInputStream(stderr); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { alive = false; }
        @Override public boolean isAlive() { return alive; }
    }
}
