package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
final class RunningTask {

    private final Long taskId;

    private final CancellationToken cancellationToken = new CancellationToken();

    private final AtomicReference<TaskCancelable> cancelable = new AtomicReference<>();

    private final ReentrantLock completionLock = new ReentrantLock();

    private volatile Future<?> future;

    private volatile boolean closed;

    RunningTask(Long taskId) {
        this.taskId = taskId;
    }

    Long taskId() {
        return taskId;
    }

    CancellationToken cancellationToken() {
        return cancellationToken;
    }

    ReentrantLock completionLock() {
        return completionLock;
    }

    void setFuture(Future<?> future) {
        this.future = future;
    }

    boolean requestCancellation(boolean mayInterruptIfRunning) {
        if (closed) {
            return false;
        }
        cancellationToken.cancel();
        cancelRegisteredResource();
        Future<?> currentFuture = future;
        if (currentFuture != null) {
            currentFuture.cancel(mayInterruptIfRunning);
        }
        return true;
    }

    void registerCancelable(TaskCancelable resource) {
        cancelable.set(resource);
        if (resource != null && cancellationToken.isCancelled()) {
            cancelRegisteredResource();
        }
    }

    void clearCancelable(TaskCancelable resource) {
        cancelable.compareAndSet(resource, null);
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        cancelable.set(null);
    }

    private void cancelRegisteredResource() {
        TaskCancelable resource = cancelable.get();
        if (resource == null) {
            return;
        }
        try {
            resource.cancel();
        } catch (Exception e) {
            log.warn("Failed to cancel task resource for task {}", taskId, e);
        }
    }
}
