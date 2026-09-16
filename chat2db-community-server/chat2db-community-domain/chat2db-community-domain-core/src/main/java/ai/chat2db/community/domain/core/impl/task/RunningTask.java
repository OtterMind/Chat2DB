package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private final Set<TaskCancelable> cancelables = new HashSet<>();

    private final ReentrantLock completionLock = new ReentrantLock();

    private final CountDownLatch executionFinished = new CountDownLatch(1);

    private volatile Future<?> future;

    private volatile boolean closed;

    private boolean resourcesCancelled;

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
        List<TaskCancelable> currentCancelables;
        synchronized (cancellationLock) {
            if (closed) {
                return false;
            }
            if (!cancellationToken.cancel()) {
                return false;
            }
            currentFuture = future;
            currentCancelables = cancelResourcesLocked();
        }
        if (currentFuture != null) {
            currentFuture.cancel(mayInterruptIfRunning);
        }
        currentCancelables.forEach(this::cancelRegisteredResourceAsync);
        return true;
    }

    void cancelResources() {
        List<TaskCancelable> resources;
        synchronized (cancellationLock) {
            resources = cancelResourcesLocked();
        }
        resources.forEach(this::cancelRegisteredResourceAsync);
    }

    private List<TaskCancelable> cancelResourcesLocked() {
        if (resourcesCancelled) {
            return List.of();
        }
        resourcesCancelled = true;
        return List.copyOf(cancelables);
    }

    void registerCancelable(TaskCancelable resource) {
        if (resource == null) {
            return;
        }
        boolean cancelImmediately;
        synchronized (cancellationLock) {
            cancelImmediately = cancelables.add(resource) && resourcesCancelled;
        }
        if (cancelImmediately) {
            cancelRegisteredResourceAsync(resource);
        }
    }

    void clearCancelable(TaskCancelable resource) {
        synchronized (cancellationLock) {
            cancelables.remove(resource);
        }
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        synchronized (cancellationLock) {
            closed = true;
            cancelables.clear();
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
