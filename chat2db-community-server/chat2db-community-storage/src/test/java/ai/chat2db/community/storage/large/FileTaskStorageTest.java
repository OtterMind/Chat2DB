package ai.chat2db.community.storage.large;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTaskStorageTest {

    @Test
    void componentScanProvidesFileTaskStorage() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan(FileTaskStorage.class.getPackageName());
            context.refresh();

            assertEquals(1, context.getBeansOfType(TaskStorage.class).size());
            assertEquals(FileTaskStorage.class, context.getBean(TaskStorage.class).getClass());
        }
    }

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
    void structuredProgressIsPersistedAndKeptByStageOnlyUpdates() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        assertTrue(start(storage, taskId));

        assertTrue(storage.updateProgressIfRunning(taskId, TaskProgress.builder()
                .progress(50)
                .stage("importing")
                .message("Imported 500 of 1000 rows")
                .processedRows(500L)
                .totalRows(1_000L)
                .rowsPerSecond(125.5d)
                .etaSeconds(4L)
                .build()));
        Task running = storage.get(taskId).orElseThrow();
        assertEquals(500L, running.getProcessedRows());
        assertEquals(1_000L, running.getTotalRows());
        assertEquals(125.5d, running.getRowsPerSecond(), 0.001d);
        assertEquals(4L, running.getEtaSeconds());

        assertTrue(storage.updateProgressIfRunning(taskId, progress(60, "finalizing")));
        Task finalizing = storage.get(taskId).orElseThrow();
        assertEquals(60, finalizing.getProgress());
        assertEquals(500L, finalizing.getProcessedRows(), "stage-only updates keep the row counters");
        assertEquals(1_000L, finalizing.getTotalRows());
        assertEquals(125.5d, finalizing.getRowsPerSecond(), 0.001d);
        assertEquals(4L, finalizing.getEtaSeconds());
    }

    @Test
    void listsTasksBeforeACursorWithoutRepeatingTheCursor() {
        FileTaskStorage storage = storage();
        Task first = create(storage, "first");
        Task second = create(storage, "second");
        Task third = create(storage, "third");

        TaskQuery query = new TaskQuery();
        query.setBeforeId(third.getId());
        query.setPageSize(10);
        PageResponse<Task> page = storage.list(query);

        assertEquals(List.of(second.getId(), first.getId()),
                page.getData().stream().map(Task::getId).toList());
        assertEquals(0L, page.getTotal());
        assertFalse(page.getHasNextPage());

        query.setPageSize(1);
        PageResponse<Task> firstPage = storage.list(query);
        assertEquals(List.of(second.getId()), firstPage.getData().stream().map(Task::getId).toList());
        assertTrue(firstPage.getHasNextPage());
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
    void incrementalReadStartsAfterCachedTailAndPreservesUtf8() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "task");
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(created.getId());
        second.setMessage("\u5df2\u5bfc\u51fa 1000 \u884c");
        storage.appendEvent(second);
        assertEquals(List.of(2L), sequences(storage.listEventsBefore(created.getId(), null, 1)));
        TaskEvent third = event(TaskEventCode.QUERY_COMPLETED.name());
        third.setTaskId(created.getId());
        third.setMessage("\u5bfc\u51fa\u5b8c\u6210");
        storage.appendEvent(third);

        List<TaskEvent> events = storage.listEvents(created.getId(), 2L, 10);

        assertEquals(List.of(3L), sequences(events));
        assertEquals("\u5bfc\u51fa\u5b8c\u6210", events.get(0).getMessage());
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

        assertFalse(storage.deleteTerminalTask(taskId, () -> {}));
        assertTrue(detailFile(taskId).isFile());
        assertTrue(eventsFile(taskId).isFile());

        assertTrue(start(storage, taskId));
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactId("artifact").finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));
        assertTrue(storage.deleteTerminalTask(taskId, () -> {}));

        assertTrue(storage.get(taskId).isEmpty());
        assertFalse(detailFile(taskId).exists());
        assertFalse(eventsFile(taskId).exists());
        assertFalse(FileUtil.readLines(indexFile(), "UTF-8").contains(String.valueOf(taskId)));

        FileTaskStorage reloaded = storage();
        assertTrue(reloaded.get(taskId).isEmpty());
        assertTrue(reloaded.listEvents(taskId, 0, 10).isEmpty());
    }

    @Test
    void terminalTaskDeletionRollsBackSnapshotAndEventsWhenCommitFails() {
        FileTaskStorage storage = storage();
        Task task = create(storage, "task");
        Long taskId = task.getId();
        assertTrue(start(storage, taskId));
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactId("artifact").finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));

        assertThrows(IllegalStateException.class,
                () -> storage.deleteTerminalTask(taskId, () -> {
                    throw new IllegalStateException("artifact commit failed");
                }));

        assertEquals(TaskStatus.SUCCESS.name(), storage.get(taskId).orElseThrow().getStatus());
        assertTrue(detailFile(taskId).isFile());
        assertTrue(eventsFile(taskId).isFile());
        assertEquals(List.of(1L, 2L, 3L), sequences(storage.listEvents(taskId, 0, 10)));
        assertTrue(FileUtil.readLines(indexFile(), "UTF-8").contains(String.valueOf(taskId)));
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

    @Test
    void appendedEventsAreImmediatelyReadableWithoutPerEventFlush() {
        CountingFileTaskStorage storage = countingStorage();
        Long taskId = create(storage, "task").getId();
        for (int i = 0; i < 5; i++) {
            TaskEvent event = event(TaskEventCode.BATCH_EXECUTED.name());
            event.setTaskId(taskId);
            storage.appendEvent(event);
        }

        assertEquals(0, storage.forceCount(), "an appended event must not fsync the whole event file");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), sequences(storage.listEvents(taskId, 0, 20)));
        assertEquals(List.of(6L), sequences(storage.listEvents(taskId, 5, 20)));
        assertEquals(0, storage.forceCount());
    }

    @Test
    void terminalTransitionFlushesTheEventFileOnceAndKeepsEveryEvent() {
        CountingFileTaskStorage storage = countingStorage();
        Long taskId = create(storage, "task").getId();
        assertTrue(start(storage, taskId));
        TaskEvent completedEvent = event(TaskEventCode.QUERY_COMPLETED.name());
        completedEvent.setTaskId(taskId);
        storage.appendEvent(completedEvent);
        assertEquals(0, storage.forceCount(), "only a terminal status flushes the event file");

        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactId("artifact").finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));

        assertEquals(List.of(taskId), storage.forcedTaskIds());
        FileTaskStorage reloaded = storage();
        assertEquals(List.of(1L, 2L, 3L, 4L), sequences(reloaded.listEvents(taskId, 0, 20)));
        assertEquals(TaskStatus.SUCCESS.name(), reloaded.get(taskId).orElseThrow().getStatus());
        assertEquals(1, storage.forceCount());
    }

    @Test
    void pendingAndRunningTasksCanBeCancelledAndCancelledIsTerminal() {
        FileTaskStorage storage = storage();
        Task queued = create(storage, "queued");
        assertTrue(storage.compareAndSetStatus(queued.getId(), TaskStatus.PENDING.name(),
                TaskStatus.CANCELLED.name(), cancelledPatch(), event(TaskEventCode.TASK_CANCELLED.name())));
        assertEquals(TaskStatus.CANCELLED.name(), storage.get(queued.getId()).orElseThrow().getStatus());
        assertFalse(storage.compareAndSetStatus(queued.getId(), TaskStatus.CANCELLED.name(),
                TaskStatus.RUNNING.name(), TaskStatusPatch.builder().build(),
                event(TaskEventCode.TASK_STARTED.name())));

        Task running = create(storage, "running");
        assertTrue(start(storage, running.getId()));
        assertTrue(storage.compareAndSetStatus(running.getId(), TaskStatus.RUNNING.name(),
                TaskStatus.CANCELLED.name(), cancelledPatch(), event(TaskEventCode.TASK_CANCELLED.name())));

        Task cancelled = storage.get(running.getId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED.name(), cancelled.getStatus());
        assertEquals(TaskStage.CANCELLED.name(), cancelled.getStage());
        assertNull(cancelled.getErrorCode());
        assertNotNull(cancelled.getFinishedAt());
        assertTrue(storage.listNonTerminalTasks().stream()
                .noneMatch(task -> running.getId().equals(task.getId())));
        assertEquals(List.of(1L, 2L, 3L), sequences(storage.listEvents(running.getId(), 0, 20)));
    }

    @Test
    void pollingReaderObservesEveryEventWhileTheTaskThreadAppends() throws Exception {
        FileTaskStorage storage = storage();
        Long taskId = create(storage, "task").getId();
        int appends = 60;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                for (int i = 0; i < appends; i++) {
                    TaskEvent event = event(TaskEventCode.BATCH_EXECUTED.name());
                    event.setTaskId(taskId);
                    storage.appendEvent(event);
                }
            });
            List<Long> observed = new CopyOnWriteArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (observed.size() < appends + 1 && System.nanoTime() < deadline) {
                long afterSequence = observed.isEmpty() ? 0L : observed.get(observed.size() - 1);
                for (TaskEvent event : storage.listEvents(taskId, afterSequence, appends + 1)) {
                    observed.add(event.getSequence());
                }
            }
            writer.get(5L, TimeUnit.SECONDS);

            List<Long> expected = LongStream.rangeClosed(1, appends + 1).boxed().toList();
            assertEquals(expected, observed, "an incremental reader must see every event in order");
            assertEquals(expected, sequences(storage.listEvents(taskId, 0, appends + 1)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void specJsonSurvivesSnapshotReloadAndIsNullWhenNotProvided() {
        FileTaskStorage storage = storage();
        String spec = "{\"format\":\"CSV\",\"conflictPolicy\":\"INSERT\"}";
        Task input = task("with-spec");
        input.setSpecJson(spec);
        Task withSpec = storage.create(input, event(TaskEventCode.TASK_CREATED.name()));
        Task withoutSpec = create(storage, "without-spec");

        assertEquals(spec, withSpec.getSpecJson());
        assertNull(withoutSpec.getSpecJson());
        assertTrue(FileUtil.readUtf8String(detailFile(withSpec.getId())).contains("\"specJson\""));

        FileTaskStorage reloaded = storage();
        assertEquals(spec, reloaded.get(withSpec.getId()).orElseThrow().getSpecJson());
        assertNull(reloaded.get(withoutSpec.getId()).orElseThrow().getSpecJson());
    }

    @Test
    void checkpointIsPersistedForRunningAndTerminalTasks() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "import");
        Long taskId = created.getId();
        assertNull(created.getCheckpointJson());

        assertFalse(storage.saveCheckpoint(999_999L, "{\"processedRows\":1}"));
        assertFalse(storage.saveCheckpoint(taskId, null));
        assertTrue(storage.saveCheckpoint(taskId,
                "{\"processedRows\":10,\"targetTable\":\"target\",\"conflictPolicy\":\"INSERT\"}"));
        assertTrue(start(storage, taskId));

        String checkpoint = "{\"processedRows\":120,\"targetTable\":\"target\",\"conflictPolicy\":\"INSERT\"}";
        assertTrue(storage.saveCheckpoint(taskId, checkpoint));

        Task running = storage.get(taskId).orElseThrow();
        assertEquals(checkpoint, running.getCheckpointJson());
        assertEquals(TaskConstants.STARTED_PROGRESS, running.getProgress());
        assertEquals(checkpoint, storage().get(taskId).orElseThrow().getCheckpointJson());

        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.CANCELLED.name(),
                TaskStatusPatch.builder().finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_CANCELLED.name())));

        // Cancellation persists the terminal status before the import stops, and the batches still
        // in flight keep committing; their final position must remain writable.
        String finalCheckpoint = "{\"processedRows\":999,\"targetTable\":\"target\",\"conflictPolicy\":\"INSERT\"}";
        assertTrue(storage.saveCheckpoint(taskId, finalCheckpoint));
        Task cancelled = storage.get(taskId).orElseThrow();
        assertEquals(finalCheckpoint, cancelled.getCheckpointJson());
        assertEquals(finalCheckpoint, storage().get(taskId).orElseThrow().getCheckpointJson());
        assertEquals(List.of(1L, 2L, 3L), sequences(storage.listEvents(taskId, 0, 20)));
    }

    @Test
    void errorArtifactIsAttachedWithoutReplacingThePrimaryArtifact() {
        FileTaskStorage storage = storage();
        Task created = create(storage, "import");
        Long taskId = created.getId();
        assertNull(created.getErrorArtifactId());

        String errorArtifact = "/tmp/tasks/7/error-rows.csv";
        String lateErrorArtifact = "/tmp/tasks/7/error-rows-late.csv";

        assertFalse(storage.attachErrorArtifact(999_999L, errorArtifact));
        assertFalse(storage.attachErrorArtifact(taskId, null));
        assertFalse(storage.attachErrorArtifact(taskId, "  "));

        assertTrue(start(storage, taskId));
        assertTrue(storage.attachErrorArtifact(taskId, errorArtifact));
        Task attached = storage.get(taskId).orElseThrow();
        assertEquals(errorArtifact, attached.getErrorArtifactId());
        assertNull(attached.getArtifactId());

        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactId("primary").finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));
        assertEquals("primary", storage.get(taskId).orElseThrow().getArtifactId());

        assertTrue(storage.attachErrorArtifact(taskId, lateErrorArtifact),
                "an error file produced after the terminal transition must stay discoverable");
        Task reloaded = storage().get(taskId).orElseThrow();
        assertEquals("primary", reloaded.getArtifactId());
        assertEquals(lateErrorArtifact, reloaded.getErrorArtifactId());
    }

    @Test
    void legacySnapshotWithoutNewFieldsReadsBackWithNulls() {
        String legacyJson = "{\"id\":42,\"type\":\"CSV_IMPORT\",\"name\":\"legacy\",\"status\":\"SUCCESS\","
                + "\"progress\":100,\"stage\":\"finished\",\"artifactId\":\"legacy-artifact\","
                + "\"createdAt\":1700000000000,\"finishedAt\":1700000000001}";
        FileUtil.writeUtf8String("42\n", indexFile());
        FileUtil.writeUtf8String(legacyJson, detailFile(42L));

        FileTaskStorage storage = storage();
        Task legacy = storage.get(42L).orElseThrow();

        assertEquals("legacy", legacy.getName());
        assertEquals(TaskStatus.SUCCESS.name(), legacy.getStatus());
        assertEquals("legacy-artifact", legacy.getArtifactId());
        assertNull(legacy.getSpecJson());
        assertNull(legacy.getCheckpointJson());
        assertNull(legacy.getErrorArtifactId());
        assertEquals(1L, storage.list(query(1, 20)).getTotal());

        assertTrue(storage.saveCheckpoint(42L, "{\"processedRows\":1}"));
        assertEquals("{\"processedRows\":1}", storage.get(42L).orElseThrow().getCheckpointJson());
        String legacyErrorArtifact = "/tmp/tasks/42/error-rows.csv";
        assertTrue(storage.attachErrorArtifact(42L, legacyErrorArtifact));
        assertEquals(legacyErrorArtifact, storage().get(42L).orElseThrow().getErrorArtifactId());
    }

    private TaskStatusPatch cancelledPatch() {
        return TaskStatusPatch.builder()
                .stage(TaskStage.CANCELLED.name())
                .progressMessage("Task cancelled")
                .finishedAt(new Date())
                .build();
    }

    private CountingFileTaskStorage countingStorage() {
        return new CountingFileTaskStorage(baseDir.getAbsolutePath());
    }

    private static final class CountingFileTaskStorage extends FileTaskStorage {

        private final AtomicInteger forces = new AtomicInteger();

        private final List<Long> forcedTaskIds = new CopyOnWriteArrayList<>();

        private CountingFileTaskStorage(String storageBasePath) {
            super(storageBasePath);
        }

        @Override
        void forceEventFile(Long taskId) {
            forcedTaskIds.add(taskId);
            forces.incrementAndGet();
            super.forceEventFile(taskId);
        }

        private int forceCount() {
            return forces.get();
        }

        private List<Long> forcedTaskIds() {
            return List.copyOf(forcedTaskIds);
        }
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
