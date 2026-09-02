package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.storage.large.FileTaskStorage;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStorageMigrationTest {

    @TempDir
    File baseDir;

    private TaskDatabase database;

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    private TaskDatabase database() {
        if (database == null) {
            database = new TaskDatabase(baseDir.getAbsolutePath());
        }
        return database;
    }

    @Test
    void importsEveryTaskAndEventAndRetiresTheLegacyDirectory() {
        FileTaskStorage file = new FileTaskStorage(baseDir.getAbsolutePath());
        Long pendingId = file.create(task("pending"), event(TaskEventCode.TASK_CREATED.name())).getId();
        Long finishedId = file.create(task("finished"), event(TaskEventCode.TASK_CREATED.name())).getId();
        assertTrue(file.compareAndSetStatus(finishedId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().progress(TaskConstants.STARTED_PROGRESS).stage("started").build(),
                event(TaskEventCode.TASK_STARTED.name())));
        TaskEvent exported = event(TaskEventCode.QUERY_COMPLETED.name());
        exported.setTaskId(finishedId);
        exported.setMessage("已导出 1200 行");
        file.appendEvent(exported);
        assertTrue(file.compareAndSetStatus(finishedId, TaskStatus.RUNNING.name(), TaskStatus.SUCCESS.name(),
                TaskStatusPatch.builder().artifactId("artifact.csv").finishedAt(new Date()).build(),
                event(TaskEventCode.TASK_SUCCEEDED.name())));
        // A killed process can leave a half-written trailing line; it must not block the import.
        FileUtil.appendUtf8String("{\"sequence\":", eventsFile(finishedId));

        assertEquals(2, new TaskStorageMigrator(database(), baseDir.getAbsolutePath()).migrateIfRequired());

        H2TaskStorage migrated = new H2TaskStorage(database());
        assertEquals(List.of(pendingId, finishedId), ids(migrated.listTasksForRecovery()));
        assertEquals(TaskStatus.PENDING.name(), migrated.get(pendingId).orElseThrow().getStatus());

        Task finished = migrated.get(finishedId).orElseThrow();
        assertEquals(TaskStatus.SUCCESS.name(), finished.getStatus());
        assertEquals(TaskConstants.COMPLETED_PROGRESS, finished.getProgress());
        assertEquals("artifact.csv", finished.getArtifactId());
        assertEquals(3L, finished.getUserId());
        assertEquals(4L, finished.getOrganizationId());

        List<TaskArtifact> migratedArtifacts = migrated.listArtifacts(finishedId);
        assertEquals(List.of("artifact.csv"),
                migratedArtifacts.stream().map(TaskArtifact::getArtifactId).toList());
        assertEquals(TaskArtifactRole.OUTPUT, migratedArtifacts.get(0).getRole());
        assertTrue(migrated.listArtifacts(pendingId).isEmpty());

        List<TaskEvent> events = migrated.listEvents(finishedId, 0, 20);
        assertEquals(List.of(1L, 2L, 3L, 4L), sequences(events));
        assertEquals("已导出 1200 行", events.get(2).getMessage());
        assertEquals(List.of(1L), sequences(migrated.listEvents(pendingId, 0, 20)));

        TaskEvent next = event(TaskEventCode.QUERY_STARTED.name());
        next.setTaskId(finishedId);
        assertEquals(5L, migrated.appendEvent(next).getSequence());

        assertFalse(legacyDirectory().isDirectory());
        File migratedDirectory = new File(baseDir, FileTaskStorage.TASK_STORAGE_DIRECTORY
                + TaskStorageMigrator.MIGRATED_DIRECTORY_SUFFIX);
        assertTrue(migratedDirectory.isDirectory());
        assertTrue(new File(migratedDirectory, finishedId + FileTaskStorage.TASK_EVENT_FILE_SUFFIX).isFile());
    }

    @Test
    void runningTheMigrationTwiceImportsNothingAgain() {
        FileTaskStorage file = new FileTaskStorage(baseDir.getAbsolutePath());
        Long taskId = file.create(task("once"), event(TaskEventCode.TASK_CREATED.name())).getId();

        TaskStorageMigrator migrator = new TaskStorageMigrator(database(), baseDir.getAbsolutePath());
        assertEquals(1, migrator.migrateIfRequired());
        assertEquals(0, migrator.migrateIfRequired());

        H2TaskStorage migrated = new H2TaskStorage(database());
        assertEquals(List.of(taskId), ids(migrated.listTasksForRecovery()));
        assertEquals(List.of(1L), sequences(migrated.listEvents(taskId, 0, 20)));
    }

    @Test
    void migratesAnAbsentLegacyDirectoryWithoutWritingTasks() {
        String storageBasePath = new File(baseDir, "unused").getAbsolutePath();
        TaskDatabase fresh = new TaskDatabase(storageBasePath);
        try {
            assertEquals(0, new TaskStorageMigrator(fresh, storageBasePath).migrateIfRequired());
            assertTrue(new H2TaskStorage(fresh).listTasksForRecovery().isEmpty());
        } finally {
            fresh.close();
        }
    }

    private Task task(String name) {
        return Task.builder()
                .type("TABLE_DATA_EXPORT")
                .name(name)
                .userId(3L)
                .organizationId(4L)
                .build();
    }

    private TaskEvent event(String code) {
        return TaskEvent.builder()
                .level("INFO")
                .code(code)
                .message(code)
                .build();
    }

    private File eventsFile(Long taskId) {
        return new File(legacyDirectory(), taskId + FileTaskStorage.TASK_EVENT_FILE_SUFFIX);
    }

    private File legacyDirectory() {
        return new File(baseDir, FileTaskStorage.TASK_STORAGE_DIRECTORY);
    }

    private List<Long> sequences(List<TaskEvent> events) {
        return events.stream().map(TaskEvent::getSequence).toList();
    }

    private List<Long> ids(List<Task> tasks) {
        return tasks.stream().map(Task::getId).sorted(Comparator.naturalOrder()).toList();
    }
}
