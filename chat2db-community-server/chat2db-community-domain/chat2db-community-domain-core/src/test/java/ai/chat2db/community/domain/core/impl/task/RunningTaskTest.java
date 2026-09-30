package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RunningTaskTest {

    @Test
    void blockingJdbcCancellationDoesNotBlockTheCancellationRequest() throws Exception {
        RunningTask runningTask = new RunningTask(42L, () -> { });
        CountDownLatch cancelStarted = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        FutureTask<Void> future = new FutureTask<>(() -> null);
        runningTask.setFuture(future);
        runningTask.registerCancelable(() -> {
            cancelStarted.countDown();
            releaseCancel.await();
        });

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertTrue(runningTask.requestCancellation(true)));
            assertTrue(future.isCancelled());
            assertTrue(cancelStarted.await(1, TimeUnit.SECONDS));
            assertFalse(runningTask.requestCancellation(true));
        } finally {
            releaseCancel.countDown();
        }
    }

    @Test
    void commitPhasePreventsLateCancellationFromInterruptingTheTask() {
        RunningTask runningTask = new RunningTask(43L, () -> { });
        FutureTask<Void> future = new FutureTask<>(() -> null);
        runningTask.setFuture(future);

        assertTrue(runningTask.enterCommitPhase());

        assertFalse(runningTask.requestCancellation(true));
        assertFalse(future.isCancelled());
        assertTrue(runningTask.isCommitPhase());
    }

    @Test
    void cancellationWonBeforeCommitPhasePreventsTheCommitBoundary() {
        RunningTask runningTask = new RunningTask(44L, () -> { });
        FutureTask<Void> future = new FutureTask<>(() -> null);
        runningTask.setFuture(future);

        assertTrue(runningTask.requestCancellation(true));

        assertFalse(runningTask.enterCommitPhase());
        assertTrue(future.isCancelled());
        assertFalse(runningTask.isCommitPhase());
    }

    @Test
    void checkpointPersistenceCompletesBeforeCancellationCanAcquireTheCompletionLock() throws Exception {
        RunningTask runningTask = new RunningTask(45L, () -> { });
        CountDownLatch checkpointWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseCheckpointWrite = new CountDownLatch(1);
        AtomicReference<ResumeState> persistedState = new AtomicReference<>();
        TaskStorage storage = (TaskStorage) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> {
                    if ("saveResumeState".equals(method.getName())) {
                        persistedState.set((ResumeState) args[1]);
                        checkpointWriteStarted.countDown();
                        if (!releaseCheckpointWrite.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out while holding the checkpoint write");
                        }
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        TaskExecutionContextImpl context = new TaskExecutionContextImpl(
                runningTask.taskId(), runningTask, storage, new ArtifactService());
        AtomicReference<Throwable> checkpointFailure = new AtomicReference<>();
        Thread checkpointThread = new Thread(() -> {
            try {
                context.checkpoint(ResumeState.builder().shardNo(0).kind("IMPORT_WATERMARK").build());
            } catch (Throwable failure) {
                checkpointFailure.set(failure);
            }
        }, "checkpoint-test");
        checkpointThread.start();
        assertTrue(checkpointWriteStarted.await(1, TimeUnit.SECONDS));

        FutureTask<Boolean> cancellation = new FutureTask<>(() -> runningTask.requestCancellation(true));
        Thread cancellationThread = new Thread(cancellation, "checkpoint-cancellation-test");
        cancellationThread.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                while (!runningTask.completionLock().hasQueuedThread(cancellationThread)) {
                    Thread.onSpinWait();
                }
            });
            assertFalse(cancellation.isDone());
        } finally {
            releaseCheckpointWrite.countDown();
        }

        assertTrue(cancellation.get(1, TimeUnit.SECONDS));
        checkpointThread.join(1000L);
        cancellationThread.join(1000L);
        assertFalse(checkpointThread.isAlive());
        assertFalse(cancellationThread.isAlive());
        assertNull(checkpointFailure.get());
        assertEquals("IMPORT_WATERMARK", persistedState.get().getKind());
        assertTrue(runningTask.cancellationToken().isCancelled());
    }
}
