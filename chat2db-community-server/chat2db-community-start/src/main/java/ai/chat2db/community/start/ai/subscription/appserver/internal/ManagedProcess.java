package ai.chat2db.community.start.ai.subscription.appserver.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

public interface ManagedProcess extends AutoCloseable {

    OutputStream stdin();

    InputStream stdout();

    InputStream stderr();

    boolean isAlive();

    void destroyForcibly();

    boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException;

    Integer exitValueIfTerminated();

    @Override
    default void close() {
        destroyForcibly();
    }
}
