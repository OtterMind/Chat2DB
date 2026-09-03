package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
final class RunningTask {

    private static final AtomicInteger CANCELLATION_THREAD_SEQUENCE = new AtomicInteger();

    private static final ExecutorService CANCELLATION_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable,
                "chat2db-task-cancel-" + CANCELLATION_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final Long taskId;

    private final Executor cancellationExecutor;

    private final CancellationToken cancellationToken = new CancellationToken();

    private final Object cancellationLock = new Object();

    private TaskCancelable cancelable;

    private final ReentrantLock completionLock = new ReentrantLock();

    private final CountDownLatch executionFinished = new CountDownLatch(1);

    private volatile Future<?> future;

    private volatile boolean closed;

    RunningTask(Long taskId) {
        this(taskId, CANCELLATION_EXECUTOR);
    }

    RunningTask(Long taskId, Executor cancellationExecutor) {
        this.taskId = taskId;
        this.cancellationExecutor = cancellationExecutor;
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
        Future<?> currentFuture;
        TaskCancelable currentCancelable;
        synchronized (cancellationLock) {
            if (closed) {
                return false;
            }
            if (!cancellationToken.cancel()) {
                return false;
            }
            currentFuture = future;
            currentCancelable = cancelable;
        }
        if (currentFuture != null) {
            currentFuture.cancel(mayInterruptIfRunning);
        }
        cancelRegisteredResourceAsync(currentCancelable);
        return true;
    }

    void registerCancelable(TaskCancelable resource) {
        boolean cancelImmediately;
        synchronized (cancellationLock) {
            cancelable = resource;
            cancelImmediately = resource != null && cancellationToken.isCancelled();
        }
        if (cancelImmediately) {
            cancelRegisteredResourceAsync(resource);
        }
    }

    void clearCancelable(TaskCancelable resource) {
        synchronized (cancellationLock) {
            if (cancelable == resource) {
                cancelable = null;
            }
        }
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        synchronized (cancellationLock) {
            closed = true;
            cancelable = null;
        }
    }

    void markFinished() {
        executionFinished.countDown();
    }

    boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
        return executionFinished.await(timeout, unit);
    }

    private void cancelRegisteredResourceAsync(TaskCancelable resource) {
        if (resource == null) {
            return;
        }
        cancellationExecutor.execute(() -> {
            try {
                resource.cancel();
            } catch (Exception e) {
                log.warn("Failed to cancel task resource for task {}", taskId, e);
            }
        });
    }
}
