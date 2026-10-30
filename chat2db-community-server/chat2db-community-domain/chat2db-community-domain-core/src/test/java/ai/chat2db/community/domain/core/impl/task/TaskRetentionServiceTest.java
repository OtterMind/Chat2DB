package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRetentionServiceTest {

    private static final long NOW = 1_000_000_000_000L;

    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    void expiresTerminalTasksPastRetention() {
        Task expired = task(1L, TaskStatus.SUCCESS, NOW - 31 * DAY);
        Task recent = task(2L, TaskStatus.FAILED, NOW - 1 * DAY);

        assertEquals(List.of(1L), ids(TaskRetentionService.selectForDeletion(
                List.of(expired, recent), NOW, Duration.ofDays(30), 500, 0L)));
    }

    @Test
    void neverSelectsNonTerminalTasks() {
        Task running = task(1L, TaskStatus.RUNNING, NOW - 60 * DAY);
        Task pending = task(2L, TaskStatus.PENDING, NOW - 60 * DAY);

        assertTrue(TaskRetentionService.selectForDeletion(
                List.of(running, pending), NOW, Duration.ofDays(30), 1, 1L).isEmpty());
    }

    @Test
    void retentionCanBeDisabled() {
        Task expired = task(1L, TaskStatus.SUCCESS, NOW - 60 * DAY);

        assertTrue(TaskRetentionService.selectForDeletion(
                List.of(expired), NOW, Duration.ZERO, 0, 0L).isEmpty());
    }

    @Test
    void trimsOldestTerminalTasksBeyondTheCountLimit() {
        List<Task> tasks = List.of(
                task(1L, TaskStatus.SUCCESS, NOW - 5 * DAY),
                task(2L, TaskStatus.SUCCESS, NOW - 4 * DAY),
                task(3L, TaskStatus.SUCCESS, NOW - 3 * DAY),
                task(4L, TaskStatus.SUCCESS, NOW - 2 * DAY),
                task(5L, TaskStatus.SUCCESS, NOW - 1 * DAY));

        assertEquals(List.of(1L, 2L), ids(TaskRetentionService.selectForDeletion(
                tasks, NOW, Duration.ofDays(30), 3, 0L)));
    }

    @Test
    void trimsOldestTerminalTasksBeyondTheArtifactBudget(@TempDir Path directory) throws Exception {
        long size = 60L;
        Task oldest = withArtifact(task(1L, TaskStatus.SUCCESS, NOW - 3 * DAY), directory, "old.csv", size);
        Task newest = withArtifact(task(2L, TaskStatus.SUCCESS, NOW - 1 * DAY), directory, "new.csv", size);

        assertEquals(List.of(1L), ids(TaskRetentionService.selectForDeletion(
                List.of(newest, oldest), NOW, Duration.ofDays(30), 500, 100L)));
    }

    @Test
    void countsTheErrorArtifactTowardsTheBudget(@TempDir Path directory) throws Exception {
        Task task = withArtifact(task(1L, TaskStatus.SUCCESS, NOW - 1 * DAY), directory, "out.csv", 60L);
        Path error = directory.resolve("error.csv");
        Files.write(error, new byte[60]);
        task.setErrorArtifactId(error.toString());

        assertEquals(120L, TaskRetentionService.artifactBytes(task));
    }

    private static Task withArtifact(Task task, Path directory, String name, long size) throws Exception {
        Path file = directory.resolve(name);
        Files.write(file, new byte[(int) size]);
        task.setArtifactId(file.toString());
        return task;
    }

    private static Task task(long id, TaskStatus status, long finishedAtMillis) {
        return Task.builder()
                .id(id)
                .status(status.name())
                .createdAt(new Date(finishedAtMillis))
                .finishedAt(new Date(finishedAtMillis))
                .build();
    }

    private static List<Long> ids(List<Task> tasks) {
        return tasks.stream().map(Task::getId).toList();
    }
}
