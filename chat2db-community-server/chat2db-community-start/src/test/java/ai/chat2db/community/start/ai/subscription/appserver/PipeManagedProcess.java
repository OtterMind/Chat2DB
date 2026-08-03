package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.internal.ManagedProcess;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process ManagedProcess backed by pipes, used with {@link FakeAppServer}.
 * Close order: signal stop → close pipe ends (unblocks readers) → join fake thread → close fake.
 */
public final class PipeManagedProcess implements ManagedProcess {

    private final PipedOutputStream clientStdin;
    private final PipedInputStream clientStdout;
    private final PipedInputStream serverStdin;
    private final PipedOutputStream serverStdout;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final FakeAppServer fake;
    private final Thread fakeThread;

    public PipeManagedProcess() throws IOException {
        this.clientStdin = new PipedOutputStream();
        this.serverStdin = new PipedInputStream(clientStdin, 64 * 1024);
        this.serverStdout = new PipedOutputStream();
        this.clientStdout = new PipedInputStream(serverStdout, 64 * 1024);
        this.fake = new FakeAppServer(serverStdin, serverStdout);
        this.fakeThread = new Thread(fake, "fake-app-server");
        this.fakeThread.setDaemon(true);
        this.fakeThread.start();
    }

    public FakeAppServer fake() {
        return fake;
    }

    @Override
    public OutputStream stdin() {
        return clientStdin;
    }

    @Override
    public InputStream stdout() {
        return clientStdout;
    }

    @Override
    public InputStream stderr() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public boolean isAlive() {
        return alive.get();
    }

    @Override
    public void destroyForcibly() {
        if (!alive.compareAndSet(true, false)) {
            return;
        }
        // 1) Tell fake to stop accepting work without closing its reader yet.
        fake.requestStop();
        // 2) Close all pipe ends so any blocked readLine()/write unblocks with IOException.
        closeQuietly(clientStdin);
        closeQuietly(serverStdout);
        closeQuietly(serverStdin);
        closeQuietly(clientStdout);
        // 3) Interrupt and join the fake thread so it is not holding the reader monitor.
        fakeThread.interrupt();
        try {
            fakeThread.join(2_000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        // 4) Now it is safe to close the fake's BufferedReader/Writer.
        fake.close();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
        destroyForcibly();
        return true;
    }

    @Override
    public Integer exitValueIfTerminated() {
        return alive.get() ? null : 0;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
