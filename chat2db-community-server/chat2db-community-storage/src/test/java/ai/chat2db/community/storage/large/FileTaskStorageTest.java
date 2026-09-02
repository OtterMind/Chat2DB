package ai.chat2db.community.storage.large;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.storage.AbstractTaskStorageContractTest;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-layout behaviour of {@link FileTaskStorage}. The storage-independent contract lives in
 * {@link AbstractTaskStorageContractTest}.
 */
class FileTaskStorageTest extends AbstractTaskStorageContractTest {

    @Override
    protected TaskStorage createStorage() {
        return new FileTaskStorage(baseDir.getAbsolutePath());
    }

    private FileTaskStorage fileStorage() {
        return (FileTaskStorage) storage();
    }

    @Test
    void usesTaskV2LayoutAndIgnoresLegacyTaskDirectory() {
        File legacyDirectory = new File(baseDir, "task");
        File legacyIndex = new File(legacyDirectory, "task.json");
        FileUtil.writeUtf8String("42\n", legacyIndex);
        FileUtil.writeUtf8String(JSON.toJSONString(Task.builder().id(42L).name("legacy").build()),
                new File(legacyDirectory, "42.json"));

        FileTaskStorage storage = fileStorage();
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
        FileTaskStorage storage = fileStorage();

        for (int i = 0; i < 25; i++) {
            create(storage, "task-" + i);
        }

        assertEquals(25L, storage.list(query(1, 100)).getTotal());
        assertEquals(25, FileUtil.readLines(indexFile(), "UTF-8").size());
        File[] files = taskDirectory().listFiles();
        assertNotNull(files);
        assertEquals(51, files.length, "one index plus one snapshot and one event file per task");

        FileTaskStorage reloaded = fileStorage();
        List<Task> reloadedTasks = reloaded.list(query(1, 100)).getData();
        assertEquals(25, reloadedTasks.size());
        for (Task task : reloadedTasks) {
            assertEquals(List.of(1L), sequences(reloaded.listEvents(task.getId(), 0, 10)));
        }
    }

    @Test
    void incompleteTrailingEventIsDiscardedWithoutLosingNextAppendedEvent() {
        FileTaskStorage storage = fileStorage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(taskId);
        storage.appendEvent(second);
        FileUtil.appendUtf8String("{\"sequence\":", eventsFile(taskId));

        FileTaskStorage firstReload = fileStorage();
        assertEquals(List.of(1L, 2L), sequences(firstReload.listEvents(taskId, 0, 20)));
        TaskEvent third = event(TaskEventCode.QUERY_COMPLETED.name());
        third.setTaskId(taskId);
        assertEquals(3L, firstReload.appendEvent(third).getSequence());

        FileTaskStorage secondReload = fileStorage();
        assertEquals(List.of(1L, 2L, 3L), sequences(secondReload.listEvents(taskId, 0, 20)));
    }

    @Test
    void unreadableMiddleEventDoesNotHideLaterValidEvents() {
        FileTaskStorage storage = fileStorage();
        Task created = create(storage, "task");
        Long taskId = created.getId();
        TaskEvent second = event(TaskEventCode.QUERY_STARTED.name());
        second.setTaskId(taskId);
        storage.appendEvent(second);
        FileUtil.appendUtf8String("{not-valid-json}\n", eventsFile(taskId));
        TaskEvent third = event(TaskEventCode.QUERY_COMPLETED.name());
        third.setTaskId(taskId);
        assertEquals(3L, storage.appendEvent(third).getSequence());

        FileTaskStorage reloaded = fileStorage();

        assertEquals(List.of(1L, 2L, 3L), sequences(reloaded.listEvents(taskId, 0, 20)));
        assertEquals(List.of(2L, 3L), sequences(reloaded.listEventsBefore(taskId, null, 2)));
    }

    @Test
    void residualTransitionJournalRestoresLifecycleEventAndSnapshot() {
        FileTaskStorage storage = fileStorage();
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

        FileTaskStorage reloaded = fileStorage();

        Task recovered = reloaded.get(taskId).orElseThrow();
        assertEquals(TaskStatus.SUCCESS.name(), recovered.getStatus());
        assertEquals("artifact.csv", recovered.getArtifactId());
        assertEquals(List.of(1L, 2L, 3L), sequences(reloaded.listEvents(taskId, 0, 20)));
        assertFalse(transitionFile.exists());
    }

    @Test
    void deletesTerminalTaskSnapshotIndexAndEventsButRejectsActiveTask() {
        FileTaskStorage storage = fileStorage();
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

        assertFalse(detailFile(taskId).exists());
        assertFalse(eventsFile(taskId).exists());
        assertFalse(FileUtil.readLines(indexFile(), "UTF-8").contains(String.valueOf(taskId)));
    }

    @Test
    void failedTerminalTaskDeletionKeepsSnapshotFileAndIndexEntry() {
        FileTaskStorage storage = fileStorage();
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

        assertTrue(detailFile(taskId).isFile());
        assertTrue(eventsFile(taskId).isFile());
        assertTrue(FileUtil.readLines(indexFile(), "UTF-8").contains(String.valueOf(taskId)));
    }

    @Test
    void snapshotWriteFailureDoesNotChangeInMemoryProgress() {
        FileTaskStorage storage = fileStorage();
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
        FileTaskStorage storage = fileStorage();
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
}
