package ai.chat2db.community.domain.core.impl.task;

import java.util.concurrent.atomic.AtomicBoolean;

final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }
}
