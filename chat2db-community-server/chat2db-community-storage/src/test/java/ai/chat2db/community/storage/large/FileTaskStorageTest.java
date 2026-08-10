package ai.chat2db.community.storage.large;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTaskStorageTest {

    @TempDir
    File baseDir;

    @Test
    void usesTaskV2LayoutAndIgnoresLegacyTaskDirectory() {
        File legacyDirectory = new File(baseDir, "task");
        File legacyIndex = new File(legacyDirectory, "task.json");
        FileUtil.writeUtf8String("42\n", legacyIndex);
        FileUtil.writeUtf8String(JSON.toJSONString(Task.builder().id(42L).name("legacy").build()),
                new File(legacyDirectory, "42.json"));

        FileTaskStorage storage = storage();
        assertEquals(0L, storage.list(query(1, 20)).getTotal());

        Task created = create(storage, "new-task");
        File taskDirectory = taskDirectory();
        Set<String> fileNames = new HashSet<>();
        File[] files = taskDirectory.listFiles();
        assertNotNull(files);
        for (File file : files) {
            fileNames.add(file.getName());
        }

        assertEquals(Set.of("task.json", created.getId() + ".json",
                created.getId() + "-events.json"), fileNames);
        assertEquals(List.of(String.valueOf(created.getId())), FileUtil.readLines(indexFile(), "UTF-8"));
        assertEquals("42\n", FileUtil.readUtf8String(legacyIndex));
    }

    @Test
    void keepsMoreThanLegacyLimitAndReloadsEveryTaskAndEvent() {
        FileTaskStorage storage = storage();

        for (int i = 0; i < 25; i++) {
            create(storage, "task-" + i);
        }

        assertEquals(25L, storage.list(query(1, 100)).getTotal());
        assertEquals(25, FileUtil.readLines(indexFile(), "UTF-8").size());
        File[] files = taskDirectory().listFiles();
        assertNotNull(files);
        assertEquals(51, files.length, "one index plus one snapshot and one event file per task");

        FileTaskStorage reloaded = storage();
        PageResponse<Task> page = reloaded.list(query(1, 100));
        assertEquals(25L, page.getTotal());
        assertEquals(25, page.getData().size());
        for (Task task : page.getData()) {
            assertEquals(List.of(1L), sequences(reloaded.listEvents(task.getId(), 0, 10)));
        }
    }

    @Test
    void taskAndEventResultsAreDefensiveCopies() {
        FileTaskStorage storage = storage();
        Task input = task("original");
        Task created = storage.create(input, event(TaskEventCode.TASK_CREATED.name()));
        Long taskId = created.getId();

        assertNotSame(input.getTarget(), created.getTarget());
        input.setName("mutated-input");
        input.getTarget().setTableName("mutated-input-table");
        created.setName("mutated-return");
        created.getTarget().setTableName("mutated-return-table");

        Task fetched = storage.get(taskId).orElseThrow();
        assertEquals("original", fetched.getName());
        assertEquals("source_table", fetched.getTarget().getTableName());

        fetched.setName("mutated-get");
        storage.list(query(1, 20)).getData().get(0).setName("mutated-list");
        storage.listNonTerminalTasks().get(0).setName("mutated-non-terminal");
        assertEquals("original", storage.get(taskId).orElseThrow().getName());

        Map<String, Object> details = new HashMap<>();
        details.put("rows", 10);
        TaskEvent event = event(TaskEventCode.QUERY_STARTED.name());
        event.setTaskId(taskId);
        event.setDetails(details);
        TaskEvent appended = storage.appendEvent(event);
        details.put("rows", 99);
        appended.getDetails().put("rows", 88);

        TaskEvent persisted = storage.listEvents(taskId, 1, 10).get(0);
        assertEquals(10, persisted.getDetails().get("rows"));
        persisted.getDetails().put("rows", 77);
        assertEquals(10, storage.listEvents(taskId, 1, 10).get(0).getDetails().get("rows"));
    }

    @Test
    void compareAndSetEnforcesLegalTransitionsAndTerminalImmutability() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();

        assertTrue(start(storage, taskId));
        assertFalse(storage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.FAILED.name(),
                TaskStatusPatch.builder().errorCode("LATE").build(), event(TaskEventCode.TASK_FAILED.name())));

        TaskStatusPatch success = TaskStatusPatch.builder()
                .progress(40)
                .artifactId("artifact-1")
                .finishedAt(new Date())
                .build();
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                success, event(TaskEventCode.TASK_SUCCEEDED.name())));

        Task finished = storage.get(taskId).orElseThrow();
        assertEquals(TaskStatus.SUCCESS.name(), finished.getStatus());
        assertEquals(TaskConstants.COMPLETED_PROGRESS, finished.getProgress());
        assertEquals("artifact-1", finished.getArtifactId());
        assertFalse(storage.compareAndSetStatus(taskId, TaskStatus.SUCCESS.name(), TaskStatus.FAILED.name(),
                TaskStatusPatch.builder().errorCode("TOO_LATE").build(), event(TaskEventCode.TASK_FAILED.name())));
        assertEquals(TaskStatus.SUCCESS.name(), storage.get(taskId).orElseThrow().getStatus());
        assertEquals(List.of(1L, 2L, 3L), sequences(storage.listEvents(taskId, 0, 20)));
    }

    @Test
    void progressIsRunningOnlyMonotonicAndPreservedByFailure() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();

        assertFalse(storage.updateProgressIfRunning(taskId, progress(30, "query")));
        assertTrue(start(storage, taskId));
        assertTrue(storage.updateProgressIfRunning(taskId, progress(50, "query")));
        assertFalse(storage.updateProgressIfRunning(taskId, progress(40, "write")));
        assertEquals(50, storage.get(taskId).orElseThrow().getProgress());

        assertTrue(storage.updateProgressIfRunning(taskId, progress(120, "write")));
        Task running = storage.get(taskId).orElseThrow();
        assertEquals(TaskConstants.MAX_RUNNING_PROGRESS, running.getProgress());
        assertEquals("write", running.getStage());

        TaskStatusPatch failure = TaskStatusPatch.builder()
                .progress(TaskConstants.COMPLETED_PROGRESS)
                .errorCode("EXPORT_FAILED")
                .errorMessage("failed")
                .finishedAt(new Date())
                .build();
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.FAILED.name(),
                failure, event(TaskEventCode.TASK_FAILED.name())));
        Task failed = storage.get(taskId).orElseThrow();
        assertEquals(TaskConstants.MAX_RUNNING_PROGRESS, failed.getProgress());
        assertEquals("EXPORT_FAILED", failed.getErrorCode());
        assertFalse(storage.updateProgressIfRunning(taskId, progress(50, "late")));
        assertEquals(TaskConstants.MAX_RUNNING_PROGRESS, storage.get(taskId).orElseThrow().getProgress());
    }

    @Test
    void terminalStatusRaceHasExactlyOneWinnerAndOneTerminalEvent() throws Exception {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        assertTrue(start(storage, taskId));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> success = executor.submit(() -> {
                start.await();
                return storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                        TaskStatusPatch.builder().artifactId("artifact").build(),
                        event(TaskEventCode.TASK_SUCCEEDED.name()));
            });
            Future<Boolean> failure = executor.submit(() -> {
                start.await();
                return storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.FAILED.name(),
                        TaskStatusPatch.builder().errorCode("FAILED").errorMessage("failed").build(),
                        event(TaskEventCode.TASK_FAILED.name()));
            });

            start.countDown();
            assertTrue(success.get() ^ failure.get(), "exactly one terminal transition must win");
        } finally {
            executor.shutdownNow();
        }

        assertTrue(TaskStatus.isTerminal(storage.get(taskId).orElseThrow().getStatus()));
        assertEquals(3, storage.listEvents(taskId, 0, 20).size());
    }

    @Test
    void paginationIsStableNewestFirstWithIdTieBreaker() {
        FileTaskStorage storage = storage();
        List<Task> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(create(storage, "task-" + i));
        }
        Comparator<Task> newestFirst = Comparator
                .comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Task::getId, Comparator.nullsLast(Comparator.reverseOrder()));
        List<Long> expected = created.stream().sorted(newestFirst).map(Task::getId).toList();

        List<Long> firstPage = ids(storage.list(query(1, 2)).getData());
        List<Long> secondPage = ids(storage.list(query(2, 2)).getData());
        assertEquals(expected.subList(0, 2), firstPage);
        assertEquals(expected.subList(2, 4), secondPage);
        assertEquals(firstPage, ids(storage.list(query(1, 2)).getData()));

        TaskQuery hugePage = query(Integer.MAX_VALUE, 2);
        PageResponse<Task> empty = storage.list(hugePage);
        assertTrue(empty.getData().isEmpty());
        assertEquals(5L, empty.getTotal());
    }

    @Test
    @SuppressWarnings("StringOperationCanBeSimplified")
    void statusFilterUsesStringValueEquality() {
        FileTaskStorage storage = storage();
        Task pending = create(storage, "pending");
        Task running = create(storage, "running");
        assertTrue(start(storage, running.getId()));
        TaskQuery query = query(1, 20);
        query.setStatus(new String(TaskStatus.RUNNING.name()));

        PageResponse<Task> page = storage.list(query);

        assertEquals(1L, page.getTotal());
        assertEquals(List.of(running.getId()), ids(page.getData()));
        assertFalse(page.getData().stream().anyMatch(task -> pending.getId().equals(task.getId())));
    }

    @Test
    void concurrentEventsReceiveUniqueOrderedSequencesAndSupportIncrementalReads() throws Exception {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TaskEvent>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    TaskEvent event = event(TaskEventCode.QUERY_STARTED.name());
                    event.setTaskId(taskId);
                    event.setSequence(999L);
                    return storage.appendEvent(event);
                }));
            }
            start.countDown();

            List<Long> returnedSequences = new ArrayList<>();
            for (Future<TaskEvent> future : futures) {
                returnedSequences.add(future.get().getSequence());
            }
            returnedSequences.sort(Long::compareTo);
            assertEquals(LongStream.rangeClosed(2, 21).boxed().toList(), returnedSequences);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(LongStream.rangeClosed(1, 21).boxed().toList(),
                sequences(storage.listEvents(taskId, 0, 100)));
        assertEquals(List.of(11L, 12L, 13L, 14L, 15L),
                sequences(storage.listEvents(taskId, 10, 5)));
        assertEquals(List.of(17L, 18L, 19L, 20L, 21L),
                sequences(storage.listEventsBefore(taskId, null, 5)));
        assertEquals(List.of(6L, 7L, 8L, 9L, 10L),
                sequences(storage.listEventsBefore(taskId, 11L, 5)));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                sequences(storage.listEventsBefore(taskId, 6L, 10)));
        assertTrue(storage.listEventsBefore(taskId, 1L, 5).isEmpty());
    }

    @Test
    void reversePaginationPreservesUtf8EventMessages() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(created.getId());
        second.setMessage("\u8bfb\u53d6\u8868\u6570\u636e");
        storage.appendEvent(second);

        List<TaskEvent> events = storage.listEventsBefore(created.getId(), null, 1);

        assertEquals(1, events.size());
        assertEquals("\u8bfb\u53d6\u8868\u6570\u636e", events.get(0).getMessage());
    }

    @Test
    void incompleteTrailingEventIsDiscardedWithoutLosingNextAppendedEvent() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(taskId);
        storage.appendEvent(second);
        FileUtil.appendUtf8String("{\"sequence\":", eventsFile(taskId));

        FileTaskStorage firstReload = storage();
        assertEquals(List.of(1L, 2L), sequences(firstReload.listEvents(taskId, 0, 20)));
        TaskEvent third = event(TaskEventCode.QUERY_COMPLETED.name());
        third.setTaskId(taskId);
        assertEquals(3L, firstReload.appendEvent(third).getSequence());

        FileTaskStorage secondReload = storage();
        assertEquals(List.of(1L, 2L, 3L), sequences(secondReload.listEvents(taskId, 0, 20)));
    }

    @Test
    void unreadableMiddleEventDoesNotHideLaterValidEvents() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(taskId);
        storage.appendEvent(second);
        FileUtil.appendUtf8String("{not-valid-json}\n", eventsFile(taskId));
        TaskEvent third = event(TaskEventCode.QUERY_COMPLETED.name());
        third.setTaskId(taskId);
        assertEquals(3L, storage.appendEvent(third).getSequence());

        FileTaskStorage reloaded = storage();

        assertEquals(List.of(1L, 2L, 3L), sequences(reloaded.listEvents(taskId, 0, 20)));
        assertEquals(List.of(2L, 3L), sequences(reloaded.listEventsBefore(taskId, null, 2)));
    }

    @Test
    void residualTransitionJournalRestoresLifecycleEventAndSnapshot() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        assertTrue(start(storage, taskId));
        Task completed = storage.get(taskId).orElseThrow();
        completed.setStatus(TaskStatus.SUCCESS.name());
        completed.setProgress(TaskConstants.COMPLETED_PROGRESS);
        completed.setArtifactId("artifact.csv");
        completed.setFinishedAt(new Date());
        TaskEvent completedEvent = event(TaskEventCode.TASK_SUCCEEDED.name());
        completedEvent.setTaskId(taskId);
        completedEvent.setEventId(123L);
        completedEvent.setSequence(3L);
        completedEvent.setCreatedAt(new Date());
        Map<String, Object> transition = Map.of("task", completed, "event", completedEvent);
        File transitionFile = new File(taskDirectory(),
                taskId + FileTaskStorage.TASK_TRANSITION_FILE_SUFFIX);
        FileUtil.writeUtf8String(JSON.toJSONString(transition), transitionFile);

        FileTaskStorage reloaded = storage();

        Task recovered = reloaded.get(taskId).orElseThrow();
        assertEquals(TaskStatus.SUCCESS.name(), recovered.getStatus());
        assertEquals("artifact.csv", recovered.getArtifactId());
        assertEquals(List.of(1L, 2L, 3L), sequences(reloaded.listEvents(taskId, 0, 20)));
        assertFalse(transitionFile.exists());
    }

    @Test
    void deletesTerminalTaskSnapshotIndexAndEventsButRejectsActiveTask() {
        FileTaskStorage storage = storage();
        Task task = create(storage, "task");
        Long taskId = task.getId();

        assertFalse(storage.deleteTerminalTask(taskId));
        assertTrue(detailFile(taskId).isFile());
        assertTrue(eventsFile(taskId).isFile());

        assertTrue(start(storage, taskId));
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactId("artifact").finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));
        assertTrue(storage.deleteTerminalTask(taskId));

        assertTrue(storage.get(taskId).isEmpty());
        assertFalse(detailFile(taskId).exists());
        assertFalse(eventsFile(taskId).exists());
        assertFalse(FileUtil.readLines(indexFile(), "UTF-8").contains(String.valueOf(taskId)));

        FileTaskStorage reloaded = storage();
        assertTrue(reloaded.get(taskId).isEmpty());
        assertTrue(reloaded.listEvents(taskId, 0, 10).isEmpty());
    }

    @Test
    void snapshotWriteFailureDoesNotChangeInMemoryProgress() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        assertTrue(start(storage, taskId));
        File detailFile = detailFile(taskId);
        FileUtil.del(detailFile);
        FileUtil.mkdir(detailFile);

        assertThrows(RuntimeException.class,
                () -> storage.updateProgressIfRunning(taskId, progress(50, "query")));

        assertEquals(TaskConstants.STARTED_PROGRESS, storage.get(taskId).orElseThrow().getProgress());
    }

    @Test
    void eventWriteFailureRollsBackStatusInMemoryAndSnapshot() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        File eventFile = eventsFile(taskId);
        File savedEventFile = new File(taskDirectory(), taskId + "-events.saved");
        assertTrue(eventFile.renameTo(savedEventFile));
        FileUtil.mkdir(eventFile);

        assertThrows(RuntimeException.class, () -> storage.compareAndSetStatus(taskId,
                TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().progress(TaskConstants.STARTED_PROGRESS).build(),
                event(TaskEventCode.TASK_STARTED.name())));

        assertEquals(TaskStatus.PENDING.name(), storage.get(taskId).orElseThrow().getStatus());
        Task persisted = JSON.parseObject(FileUtil.readUtf8String(detailFile(taskId)), Task.class);
        assertEquals(TaskStatus.PENDING.name(), persisted.getStatus());
        FileUtil.del(eventFile);
        assertTrue(savedEventFile.renameTo(eventFile));
        assertEquals(List.of(1L), sequences(storage.listEvents(taskId, 0, 20)));
    }

    private FileTaskStorage storage() {
        return new FileTaskStorage(baseDir.getAbsolutePath());
    }

    private Task create(FileTaskStorage storage, String name) {
        return storage.create(task(name), event(TaskEventCode.TASK_CREATED.name()));
    }

    private Task task(String name) {
        return Task.builder()
                .type("QUERY_RESULT_EXPORT")
                .name(name)
                .target(TaskTargetSnapshot.builder()
                        .dataSourceId(1L)
                        .databaseName("database")
                        .schemaName("schema")
                        .tableName("source_table")
                        .build())
                .build();
    }

    private TaskEvent event(String code) {
        return TaskEvent.builder()
                .level(TaskEventLevel.INFO.name())
                .code(code)
                .message(code)
                .build();
    }

    private boolean start(FileTaskStorage storage, Long taskId) {
        return storage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder()
                        .progress(TaskConstants.STARTED_PROGRESS)
                        .stage("started")
                        .startedAt(new Date())
                        .build(),
                event(TaskEventCode.TASK_STARTED.name()));
    }

    private TaskProgress progress(int value, String stage) {
        return TaskProgress.builder().progress(value).stage(stage).message(stage).build();
    }

    private TaskQuery query(int pageNo, int pageSize) {
        TaskQuery query = new TaskQuery();
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        return query;
    }

    private File taskDirectory() {
        return new File(baseDir, FileTaskStorage.TASK_STORAGE_DIRECTORY);
    }

    private File indexFile() {
        return new File(taskDirectory(), FileTaskStorage.TASK_INDEX_NAME + ".json");
    }

    private File detailFile(Long taskId) {
        return new File(taskDirectory(), taskId + ".json");
    }

    private File eventsFile(Long taskId) {
        return new File(taskDirectory(), taskId + FileTaskStorage.TASK_EVENT_FILE_SUFFIX);
    }

    private List<Long> sequences(List<TaskEvent> events) {
        return events.stream().map(TaskEvent::getSequence).toList();
    }

    private List<Long> ids(List<Task> tasks) {
        return tasks.stream().map(Task::getId).toList();
    }
}
