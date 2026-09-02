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
