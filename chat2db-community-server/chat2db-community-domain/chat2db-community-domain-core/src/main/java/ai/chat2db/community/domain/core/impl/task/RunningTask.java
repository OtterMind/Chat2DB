package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final CancellationToken cancellationToken = new CancellationToken();

    private final Runnable terminalResourceCleanup;

    private final AtomicBoolean terminalResourceCleanupStarted = new AtomicBoolean();

    // Several shard workers register statements concurrently; cancellation must reach all of them.
    private final Set<TaskCancelable> cancelables = ConcurrentHashMap.newKeySet();

    private final ReentrantLock completionLock = new ReentrantLock();

    private final CountDownLatch executionFinished = new CountDownLatch(1);

    private volatile Future<?> future;

    private volatile boolean closed;

    private volatile int commitPhaseDepth;

    private volatile boolean retainResourcesForResume;

    /**
     * Set when execution has already failed and the registered work must be torn down. Kept apart
     * from the cancellation token on purpose: tearing down after a failure must not look like a
     * user cancellation to the task's own status or to its resume bookkeeping.
     */
    private volatile boolean resourcesCancelled;

    RunningTask(Long taskId) {
        this(taskId, null);
    }

    RunningTask(Long taskId, Runnable terminalResourceCleanup) {
        this.taskId = taskId;
        this.terminalResourceCleanup = terminalResourceCleanup == null ? () -> { } : terminalResourceCleanup;
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
        completionLock.lock();
        try {
            if (closed || commitPhaseDepth > 0) {
                return false;
            }
            if (!cancellationToken.cancel()) {
                return false;
            }
            Future<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(mayInterruptIfRunning);
            }
            for (TaskCancelable resource : cancelables) {
                cancelRegisteredResourceAsync(resource);
            }
            return true;
        } finally {
            completionLock.unlock();
        }
    }

    boolean enterCommitPhase() {
        completionLock.lock();
        try {
            if (closed || cancellationToken.isCancelled()) {
                return false;
            }
            commitPhaseDepth++;
            return true;
        } finally {
            completionLock.unlock();
        }
    }

    boolean isCommitPhase() {
        return commitPhaseDepth > 0;
    }

    void exitCommitPhase() {
        completionLock.lock();
        try {
            if (commitPhaseDepth <= 0) {
                throw new IllegalStateException("Task is not in a commit phase");
            }
            commitPhaseDepth--;
        } finally {
            completionLock.unlock();
        }
    }

    void retainResourcesForResume() {
        retainResourcesForResume = true;
    }

    boolean shouldRetainResourcesForResume() {
        return retainResourcesForResume;
    }

    /**
     * Tears down the work this task registered because execution failed, without touching the
     * cancellation token. The flag is raised before the snapshot so a resource registered
     * concurrently observes it and cancels itself instead of being missed.
     */
    void cancelResources() {
        resourcesCancelled = true;
        for (TaskCancelable resource : cancelables) {
            cancelRegisteredResourceAsync(resource);
        }
    }

    void registerCancelable(TaskCancelable resource) {
        if (resource == null) {
            return;
        }
        cancelables.add(resource);
        if (resourcesCancelled || cancellationToken.isCancelled()) {
            cancelRegisteredResourceAsync(resource);
        }
    }

    void clearCancelable(TaskCancelable resource) {
        cancelables.remove(resource);
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        cancelables.clear();
    }

    void markFinished() {
        executionFinished.countDown();
    }

    void cleanupTerminalResources() {
        if (!terminalResourceCleanupStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            terminalResourceCleanup.run();
        } catch (RuntimeException cleanupFailure) {
            terminalResourceCleanupStarted.set(false);
            log.warn("Failed to clean terminal resources for task {}", taskId, cleanupFailure);
        }
    }

    boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
        return executionFinished.await(timeout, unit);
    }

    private void cancelRegisteredResourceAsync(TaskCancelable resource) {
        if (resource == null) {
            return;
        }
        CANCELLATION_EXECUTOR.execute(() -> {
            try {
                resource.cancel();
            } catch (Exception e) {
                log.warn("Failed to cancel task resource for task {}", taskId, e);
            }
        });
    }
}
