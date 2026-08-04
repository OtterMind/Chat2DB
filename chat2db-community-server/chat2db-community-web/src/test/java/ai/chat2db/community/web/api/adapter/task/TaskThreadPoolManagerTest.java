package ai.chat2db.community.web.api.adapter.task;

import ai.chat2db.community.domain.api.model.async.AsyncContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskThreadPoolManagerTest {

    @Test
    @SuppressWarnings("unchecked")
    void cancelTaskInterruptsTaskAndPreventsCompletionSideEffect() throws Exception {
        Long taskId = 990001L;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean ranToCompletion = new AtomicBoolean(false);
        AsyncContext asyncContext = new AsyncContext(null, null, null, false);
        TaskThread task = new TaskThread(null, asyncContext, taskId, () -> {
            started.countDown();
            try {
                blocker.await(10, TimeUnit.SECONDS);
                ranToCompletion.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });

        try {
            TaskThreadPoolManager.submitTask(taskId, task);
            assertTrue(started.await(5, TimeUnit.SECONDS), "task thread did not start");

            assertTrue(TaskThreadPoolManager.cancelTask(taskId));
            task.join(5000);
        } finally {
            blocker.countDown();
        }

        assertFalse(task.isAlive(), "cancelled task must terminate after interruption");
        assertTrue(interrupted.get(), "cancel must interrupt a blocking task");
        assertFalse(ranToCompletion.get(), "cancelled task must not execute its completion side effect");
        assertTrue(asyncContext.isStopped(), "cancelled task status must remain STOP");
        assertFalse(taskMap().containsKey(taskId), "cancelled task must not linger in taskMap");
    }

    @Test
    void cancelTaskOnUnknownTaskIdIsNoOp() {
        assertFalse(TaskThreadPoolManager.cancelTask(990002L));
    }

    @Test
    void cancelTaskStopsCooperativeWorkLoop() throws Exception {
        Long taskId = 990003L;
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger sideEffects = new AtomicInteger();
        AsyncContext asyncContext = new AsyncContext(null, null, null, false);
        TaskThread task = new TaskThread(null, asyncContext, taskId, () -> {
            while (true) {
                asyncContext.checkCancelled();
                sideEffects.incrementAndGet();
                started.countDown();
                Thread.onSpinWait();
            }
        });

        try {
            TaskThreadPoolManager.submitTask(taskId, task);
            assertTrue(started.await(5, TimeUnit.SECONDS), "task thread did not start");
            assertTrue(TaskThreadPoolManager.cancelTask(taskId));
            task.join(5000);
        } finally {
            if (task.isAlive()) {
                task.cancel();
                task.join(5000);
            }
        }

        assertFalse(task.isAlive(), "cooperative task must terminate after cancellation");
        int countAfterStop = sideEffects.get();
        Thread.sleep(20);
        assertTrue(countAfterStop > 0, "test task did not execute any work");
        assertTrue(asyncContext.isStopped());
        assertEquals(countAfterStop, sideEffects.get(), "task produced side effects after STOP");
    }

    @Test
    void completedOldTaskDoesNotRemoveReplacementWithSameId() throws Exception {
        Long taskId = 990004L;
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        TaskThread oldTask = new TaskThread(null, new AsyncContext(null, null, null, false), taskId, () -> {
            oldStarted.countDown();
            await(releaseOld);
        });
        TaskThread replacement = new TaskThread(
                null, new AsyncContext(null, null, null, false), taskId, () -> {
                    replacementStarted.countDown();
                    await(releaseReplacement);
                });

        try {
            TaskThreadPoolManager.submitTask(taskId, oldTask);
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS), "old task did not start");
            TaskThreadPoolManager.submitTask(taskId, replacement);
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS), "replacement task did not start");

            releaseOld.countDown();
            oldTask.join(5000);

            assertFalse(oldTask.isAlive());
            assertSame(replacement, taskMap().get(taskId));
        } finally {
            releaseOld.countDown();
            releaseReplacement.countDown();
            oldTask.join(5000);
            replacement.join(5000);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, TaskThread> taskMap() throws Exception {
        Field field = TaskThreadPoolManager.class.getDeclaredField("taskMap");
        field.setAccessible(true);
        return (Map<Long, TaskThread>) field.get(null);
    }
}
