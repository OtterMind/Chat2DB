package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class RestartCoordinator {

    @FunctionalInterface
    interface RestartStarter {
        void start() throws IOException;
    }

    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);

    boolean prepare(RestartStarter starter) throws IOException {
        if (!restartScheduled.compareAndSet(false, true)) {
            return false;
        }
        try {
            starter.start();
            return true;
        } catch (IOException | RuntimeException exception) {
            restartScheduled.set(false);
            throw exception;
        }
    }
}
