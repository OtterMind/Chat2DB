package ai.chat2db.community.runtime.provider;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;

public interface ManagedProviderProcess {

    InputStream stdout();

    InputStream stderr();

    OutputStream stdin();

    boolean isAlive();

    int waitFor() throws InterruptedException;

    boolean waitFor(Duration timeout) throws InterruptedException;

    void destroy();

    void destroyForcibly();

    default long pid() {
        return -1L;
    }

    default Instant startInstant() {
        return null;
    }
}
