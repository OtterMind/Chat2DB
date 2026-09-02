package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every {@link TaskStorage} implementation owes its callers, independent of where the
 * rows live. Each implementation subclass supplies a fresh storage over {@link #baseDir}; calling
 * {@link #storage()} more than once in one test therefore means "restart the application".
 */
public abstract class AbstractTaskStorageContractTest {

    @TempDir
    protected File baseDir;

    private final List<AutoCloseable> createdStorages = new ArrayList<>();

    protected abstract TaskStorage createStorage();

    protected TaskStorage storage() {
        TaskStorage storage = createStorage();
        if (storage instanceof AutoCloseable closeable) {
            createdStorages.add(closeable);
        }
        return storage;
    }

    @AfterEach
    void closeStorages() {
        for (AutoCloseable storage : createdStorages) {
            try {
                storage.close();
            } catch (Exception e) {
                // Storage shutdown is best effort; a locked file must not mask the real assertions.
            }
        }
        createdStorages.clear();
    }

    @Test
    void taskAndEventResultsAreDefensiveCopies() {
        TaskStorage storage = storage();
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
        TaskStorage storage = storage();
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
        TaskStorage storage = storage();
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
        TaskStorage storage = storage();
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
        TaskStorage storage = storage();
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
        TaskStorage storage = storage();
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
        TaskStorage storage = storage();
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
        TaskStorage storage = storage();
        Task created = create(storage, "task");
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(created.getId());
        second.setMessage("读取表数据");
        storage.appendEvent(second);

        List<TaskEvent> events = storage.listEventsBefore(created.getId(), null, 1);

        assertEquals(1, events.size());
        assertEquals("读取表数据", events.get(0).getMessage());
    }

    @Test
    void incrementalReadStartsAfterGivenSequenceAndPreservesUtf8() {
        TaskStorage storage = storage();
        Task created = create(storage, "task");
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(created.getId());
        second.setMessage("已导出 1000 行");
        storage.appendEvent(second);
        assertEquals(List.of(2L), sequences(storage.listEventsBefore(created.getId(), null, 1)));
        TaskEvent third = event(TaskEventCode.QUERY_COMPLETED.name());
        third.setTaskId(created.getId());
        third.setMessage("导出完成");
        storage.appendEvent(third);

        List<TaskEvent> events = storage.listEvents(created.getId(), 2L, 10);

        assertEquals(List.of(3L), sequences(events));
        assertEquals("导出完成", events.get(0).getMessage());
    }

    @Test
    void terminalTaskIsDeletedButActiveTaskIsRejected() {
        TaskStorage storage = storage();
        Task task = create(storage, "task");
        Long taskId = task.getId();

        assertFalse(storage.deleteTerminalTask(taskId, () -> {}));
        assertTrue(storage.get(taskId).isPresent());

        assertTrue(start(storage, taskId));
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactId("artifact").finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));
        assertTrue(storage.deleteTerminalTask(taskId, () -> {}));

        assertTrue(storage.get(taskId).isEmpty());
        assertTrue(storage.listEvents(taskId, 0, 10).isEmpty());
        assertTrue(storage.list(query(1, 20)).getData().isEmpty());

        TaskStorage reloaded = storage();
        assertTrue(reloaded.get(taskId).isEmpty());
        assertTrue(reloaded.listEvents(taskId, 0, 10).isEmpty());
    }

    @Test
    void failedTerminalTaskDeletionKeepsSnapshotAndEvents() {
        TaskStorage storage = storage();
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
        assertEquals(List.of(1L, 2L, 3L), sequences(storage.listEvents(taskId, 0, 10)));
    }

    @Test
    void scopeFilterMatchesNullColumnsOnlyAgainstNullQueryValues() {
        TaskStorage storage = storage();
        Task own = task("own");
        own.setUserId(1L);
        own.setOrganizationId(2L);
        Long ownId = storage.create(own, event(TaskEventCode.TASK_CREATED.name())).getId();
        Long unscopedId = create(storage, "unscoped").getId();

        TaskQuery scoped = query(1, 20);
        scoped.setUserId(1L);
        scoped.setOrganizationId(2L);
        assertEquals(List.of(ownId), ids(storage.list(scoped).getData()));
        assertEquals(List.of(unscopedId), ids(storage.list(query(1, 20)).getData()));

        TaskQuery otherUser = query(1, 20);
        otherUser.setUserId(99L);
        otherUser.setOrganizationId(2L);
        assertEquals(0L, storage.list(otherUser).getTotal());
    }

    @Test
    void appendEventRejectsUnknownTask() {
        TaskStorage storage = storage();
        create(storage, "task");
        TaskEvent event = event(TaskEventCode.QUERY_STARTED.name());
        event.setTaskId(-1L);

        assertThrows(IllegalArgumentException.class, () -> storage.appendEvent(event));
    }

    @Test
    void statusTransitionWithoutLifecycleEventLeavesTaskUnchanged() {
        TaskStorage storage = storage();
        Long taskId = create(storage, "task").getId();

        assertThrows(IllegalArgumentException.class, () -> storage.compareAndSetStatus(taskId,
                TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().progress(TaskConstants.STARTED_PROGRESS).build(), null));

        assertEquals(TaskStatus.PENDING.name(), storage.get(taskId).orElseThrow().getStatus());
        assertEquals(List.of(1L), sequences(storage.listEvents(taskId, 0, 10)));
    }

    @Test
    void artifactsAreRecordedPerTaskReplacedByIdAndWipedWithTheTask() {
        TaskStorage storage = storage();
        Long taskId = create(storage, "task").getId();
        Long otherTaskId = create(storage, "other").getId();
        storage.saveArtifact(taskId, artifact("artifact-1", TaskArtifactRole.OUTPUT, "text/csv", 10L));
        storage.saveArtifact(taskId, artifact("artifact-2", "REJECT", "application/x-ndjson", 20L));
        storage.saveArtifact(otherTaskId, artifact("artifact-1", TaskArtifactRole.OUTPUT, "text/csv", 5L));

        assertEquals(Set.of("artifact-1", "artifact-2"),
                storage.listArtifacts(taskId).stream().map(TaskArtifact::getArtifactId)
                        .collect(Collectors.toSet()));
        assertEquals(List.of("artifact-1"),
                storage.listArtifacts(otherTaskId).stream().map(TaskArtifact::getArtifactId).toList());

        storage.saveArtifact(taskId, artifact("artifact-1", TaskArtifactRole.OUTPUT, "text/plain", 99L));
        List<TaskArtifact> replaced = storage.listArtifacts(taskId);
        assertEquals(2, replaced.size());
        TaskArtifact updated = replaced.stream()
                .filter(candidate -> "artifact-1".equals(candidate.getArtifactId()))
                .findFirst().orElseThrow();
        assertEquals("text/plain", updated.getMediaType());
        assertEquals(99L, updated.getSizeBytes());

        storage.deleteArtifact(taskId, "artifact-2");
        storage.deleteArtifact(taskId, "missing-artifact");
        assertEquals(List.of("artifact-1"), storage.listArtifacts(taskId).stream()
                .map(TaskArtifact::getArtifactId).toList());
        assertEquals(List.of("artifact-1"), storage.get(taskId).orElseThrow().getArtifacts().stream()
                .map(TaskArtifact::getArtifactId).toList());
    }

    @Test
    void artifactAndResumeStateRejectUnknownTasks() {
        TaskStorage storage = storage();
        create(storage, "task");

        assertThrows(IllegalArgumentException.class,
                () -> storage.saveArtifact(-1L, artifact("artifact-1", TaskArtifactRole.OUTPUT, "text/csv", 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> storage.saveResumeState(-1L, resumeState(0, 10L)));
    }

    @Test
    void terminalTaskDeletionRemovesArtifactsAndResumeStates() {
        TaskStorage storage = storage();
        Long taskId = create(storage, "task").getId();
        assertTrue(start(storage, taskId));
        storage.saveArtifact(taskId, artifact("artifact-1", TaskArtifactRole.OUTPUT, "text/csv", 10L));
        storage.saveResumeState(taskId, resumeState(0, 100L));
        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactIds(List.of("artifact-1")).finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));
        assertEquals("artifact-1", storage.get(taskId).orElseThrow().getArtifactId());

        assertTrue(storage.deleteTerminalTask(taskId, () -> {}));

        TaskStorage reloaded = storage();
        assertTrue(reloaded.listArtifacts(taskId).isEmpty());
        assertTrue(reloaded.listResumeStates(taskId).isEmpty());
    }

    @Test
    void nonTerminalTasksWithResumeStatesAreListedAsResumable() {
        TaskStorage storage = storage();
        Long runningId = create(storage, "running").getId();
        Long finishedId = create(storage, "finished").getId();
        Long plainId = create(storage, "plain").getId();
        assertTrue(start(storage, runningId));
        assertTrue(start(storage, finishedId));
        assertTrue(storage.compareAndSetStatus(finishedId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));

        storage.saveResumeState(runningId, resumeState(0, 100L));
        storage.saveResumeState(finishedId, resumeState(0, 100L));
        storage.saveResumeState(finishedId, resumeState(1, 200L));

        assertEquals(List.of(runningId), ids(storage.listResumableTasks()));

        storage.clearResumeStates(runningId);
        assertTrue(storage.listResumeStates(runningId).isEmpty());
        assertTrue(storage.listResumableTasks().isEmpty());
        assertEquals(2, storage.listResumeStates(finishedId).size());
    }

    @Test
    void resumeStatesKeepTheirFieldsAndAreSortedByShard() {
        TaskStorage storage = storage();
        Long taskId = create(storage, "task").getId();
        storage.saveResumeState(taskId, resumeState(2, 300L));
        storage.saveResumeState(taskId, resumeState(0, 100L));
        storage.saveResumeState(taskId, resumeState(0, 250L));

        List<ResumeState> states = storage.listResumeStates(taskId);

        assertEquals(List.of(0, 2), states.stream().map(ResumeState::getShardNo).toList());
        assertEquals(250L, states.get(0).getRowsDone());
        assertEquals("KEYSET", states.get(1).getKind());
        assertEquals("{\"lastKey\":300}", states.get(1).getCursorJson());
        assertEquals(3000L, states.get(1).getBytesDone());
    }

    @Test
    void runningTaskCanBeRequeuedToPendingForResume() {
        TaskStorage storage = storage();
        Long taskId = create(storage, "task").getId();
        assertTrue(start(storage, taskId));

        assertTrue(storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.PENDING.name(),
                TaskStatusPatch.builder().stage(TaskStage.RESUMING.name()).build(),
                event(TaskEventCode.RESUME_AVAILABLE.name())));

        Task requeued = storage.get(taskId).orElseThrow();
        assertEquals(TaskStatus.PENDING.name(), requeued.getStatus());
        assertEquals(TaskStage.RESUMING.name(), requeued.getStage());
        assertEquals(List.of(1L, 2L, 3L), sequences(storage.listEvents(taskId, 0, 20)));
    }

    protected Task create(TaskStorage storage, String name) {
        return storage.create(task(name), event(TaskEventCode.TASK_CREATED.name()));
    }

    protected Task task(String name) {
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

    protected TaskEvent event(String code) {
        return TaskEvent.builder()
                .level(TaskEventLevel.INFO.name())
                .code(code)
                .message(code)
                .build();
    }

    protected boolean start(TaskStorage storage, Long taskId) {
        return storage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder()
                        .progress(TaskConstants.STARTED_PROGRESS)
                        .stage("started")
                        .startedAt(new Date())
                        .build(),
                event(TaskEventCode.TASK_STARTED.name()));
    }

    protected TaskProgress progress(int value, String stage) {
        return TaskProgress.builder().progress(value).stage(stage).message(stage).build();
    }

    protected TaskQuery query(int pageNo, int pageSize) {
        TaskQuery query = new TaskQuery();
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        return query;
    }

    protected List<Long> sequences(List<TaskEvent> events) {
        return events.stream().map(TaskEvent::getSequence).toList();
    }

    protected List<Long> ids(List<Task> tasks) {
        return tasks.stream().map(Task::getId).toList();
    }

    protected TaskArtifact artifact(String artifactId, String role, String mediaType, Long sizeBytes) {
        return TaskArtifact.builder()
                .artifactId(artifactId)
                .role(role)
                .mediaType(mediaType)
                .sizeBytes(sizeBytes)
                .createdAt(new Date())
                .build();
    }

    protected ResumeState resumeState(int shardNo, long rowsDone) {
        return ResumeState.builder()
                .shardNo(shardNo)
                .kind("KEYSET")
                .cursorJson("{\"lastKey\":" + rowsDone + "}")
                .rowsDone(rowsDone)
                .bytesDone(rowsDone * 10L)
                .updatedAt(new Date())
                .build();
    }
}
