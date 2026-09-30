package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.storage.AbstractTaskStorageContractTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-specific behaviour: the JDBC row mapping and the task row lock that replaces
 * {@code FileTaskStorage}'s instance-wide monitor.
 */
class H2TaskStorageTest extends AbstractTaskStorageContractTest {

    @Override
    protected TaskStorage createStorage() {
        return new H2TaskStorage(baseDir.getAbsolutePath());
    }

    @Test
    void reopensDatabaseAndRoundTripsEveryTaskField() {
        H2TaskStorage storage = (H2TaskStorage) storage();
        Task input = task("full");
        input.setUserId(7L);
        input.setOrganizationId(9L);
        Task created = storage.create(input, event(TaskEventCode.TASK_CREATED.name()));
        Long taskId = created.getId();
        Date startedAt = new Date(1_700_000_000_123L);
        Map<String, Object> details = new HashMap<>();
        details.put("rows", 10);
        details.put("table", "t_order");
        TaskEvent event = event(TaskEventCode.QUERY_STARTED.name());
        event.setTaskId(taskId);
        event.setStage("query");
        event.setDetails(details);
        storage.appendEvent(event);
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().progress(1).stage("started").startedAt(startedAt).build(),
                event(TaskEventCode.TASK_STARTED.name())));
        storage.close();

        H2TaskStorage reopened = (H2TaskStorage) storage();
        Task stored = reopened.get(taskId).orElseThrow();
        assertEquals("full", stored.getName());
        assertEquals(7L, stored.getUserId());
        assertEquals(9L, stored.getOrganizationId());
        assertEquals("database", stored.getTarget().getDatabaseName());
        assertEquals("source_table", stored.getTarget().getTableName());
        assertEquals(startedAt, stored.getStartedAt());
        assertNull(stored.getFinishedAt());
        assertEquals(TaskConstants.STARTED_PROGRESS, stored.getProgress());

        List<TaskEvent> events = reopened.listEvents(taskId, 0, 10);
        assertEquals(List.of(1L, 2L, 3L), sequences(events));
        TaskEvent storedEvent = events.get(1);
        assertEquals("query", storedEvent.getStage());
        assertEquals(10, storedEvent.getDetails().get("rows"));
        assertEquals("t_order", storedEvent.getDetails().get("table"));
    }

    @Test
    void releaseExpiredClientSubmissionsOnlyDropsFinishedTasks() {
        H2TaskStorage storage = (H2TaskStorage) storage();
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));

        Long oldFinished = createFinishedImportTask(storage, "old-finished",
                Date.from(cutoff.minus(Duration.ofHours(10))));
        Long recentFinished = createFinishedImportTask(storage, "recent-finished", new Date());
        Task running = task("still-running");
        running.setClientSubmissionId("key-running");
        running.setClientSubmissionFingerprint("fp-running");
        running.setUserId(3L);
        running.setOrganizationId(4L);
        Long runningId = storage.create(running, event(TaskEventCode.TASK_CREATED.name())).getId();
        storage.compareAndSetStatus(runningId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().progress(1).stage("started").build(),
                event(TaskEventCode.TASK_STARTED.name()));

        assertEquals(1, storage.releaseExpiredClientSubmissions(cutoff));

        assertNull(storage.get(oldFinished).orElseThrow().getClientSubmissionId());
        assertNull(storage.get(oldFinished).orElseThrow().getClientSubmissionFingerprint());
        assertEquals("key-recent-finished", storage.get(recentFinished).orElseThrow().getClientSubmissionId());
        assertEquals("key-running", storage.get(runningId).orElseThrow().getClientSubmissionId());
        assertTrue(storage.findByClientSubmissionId("key-recent-finished", 3L, 4L).isPresent());
        assertTrue(storage.findByClientSubmissionId("key-running", 3L, 4L).isPresent());
        // A second run must not report work it already did.
        assertEquals(0, storage.releaseExpiredClientSubmissions(cutoff));
    }

    @Test
    void releaseExpiredClientSubmissionsIgnoresANullCutoff() {
        H2TaskStorage storage = (H2TaskStorage) storage();
        createFinishedImportTask(storage, "kept", new Date());
        assertEquals(0, storage.releaseExpiredClientSubmissions(null));
    }

    private Long createFinishedImportTask(H2TaskStorage storage, String name, Date finishedAt) {
        Task task = task(name);
        task.setClientSubmissionId("key-" + name);
        task.setClientSubmissionFingerprint("fp-" + name);
        task.setUserId(3L);
        task.setOrganizationId(4L);
        Long taskId = storage.create(task, event(TaskEventCode.TASK_CREATED.name())).getId();
        storage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().progress(1).stage("started").build(),
                event(TaskEventCode.TASK_STARTED.name()));
        storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder()
                        .progress(TaskConstants.COMPLETED_PROGRESS)
                        .stage("finished")
                        .finishedAt(finishedAt)
                        .build(),
                event(TaskEventCode.TASK_SUCCEEDED.name()));
        return taskId;
    }

    @Test
    void separateInstancesSerializeEventSequencesByRowLock() throws Exception {
        Task created = create(storage(), "shared");
        Long taskId = created.getId();
        TaskStorage first = storage();
        TaskStorage second = storage();

        int writers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int writer = 0; writer < writers; writer++) {
                TaskStorage storage = writer % 2 == 0 ? first : second;
                futures.add(executor.submit(() -> {
                    start.await();
                    TaskEvent event = event(TaskEventCode.QUERY_STARTED.name());
                    event.setTaskId(taskId);
                    return storage.appendEvent(event).getSequence();
                }));
            }
            start.countDown();

            List<Long> returned = new ArrayList<>();
            for (Future<Long> future : futures) {
                returned.add(future.get());
            }
            returned.sort(Long::compareTo);
            assertEquals(LongStream.rangeClosed(2, 1 + writers).boxed().toList(), returned);
        } finally {
            executor.shutdownNow();
        }
    }
}
