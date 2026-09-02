package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.storage.large.FileTaskStorage;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Imports the {@code task-v2} snapshot layout written by {@code FileTaskStorage} into the H2 schema.
 *
 * <p>The import and the completion marker are written by one transaction, so a task is either fully
 * migrated or untouched, and a crash before the commit re-runs the whole import. The legacy
 * directory is renamed rather than deleted so a bad migration stays recoverable.
 */
@Slf4j
public class TaskStorageMigrator {

    static final String MIGRATION_MARKER_KEY = "task_v2_migrated";

    static final String MIGRATED_DIRECTORY_SUFFIX = ".migrated";

    private static final String TASK_FILE_SUFFIX = ".json";

    private final TaskDatabase database;

    private final File legacyDirectory;

    public TaskStorageMigrator(String storageBasePath) {
        this(new TaskDatabase(storageBasePath), storageBasePath);
    }

    TaskStorageMigrator(TaskDatabase database, String storageBasePath) {
        this.database = database;
        this.legacyDirectory = new File(storageBasePath, FileTaskStorage.TASK_STORAGE_DIRECTORY);
    }

    /**
     * @return the number of tasks imported; zero when there was nothing to migrate
     */
    public int migrateIfRequired() {
        database.initialize();
        if (isMigrated()) {
            warnAboutFilesWrittenAfterMigration();
            return 0;
        }
        Map<Long, ImportedTask> imported = readLegacyTasks();
        int count = importTasks(imported);
        renameLegacyDirectory(imported.size());
        return count;
    }

    private boolean isMigrated() {
        try (Connection connection = database.open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT meta_value FROM schema_meta WHERE meta_key = ?")) {
                statement.setString(1, MIGRATION_MARKER_KEY);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next();
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect task storage migration state", e);
        }
    }

    private Map<Long, ImportedTask> readLegacyTasks() {
        Map<Long, ImportedTask> tasks = new LinkedHashMap<>();
        for (Long taskId : readLegacyIndex()) {
            File snapshot = new File(legacyDirectory, taskId + TASK_FILE_SUFFIX);
            if (!snapshot.isFile()) {
                log.warn("Task {} is listed in the legacy index but has no snapshot", taskId);
                continue;
            }
            Task task = parseTask(snapshot);
            if (task == null || !taskId.equals(task.getId())) {
                log.warn("Skipping unreadable legacy task snapshot {}", snapshot);
                continue;
            }
            List<TaskEvent> events = readLegacyEvents(taskId);
            long lastSequence = events.stream().mapToLong(TaskEvent::getSequence).max().orElse(0L);
            tasks.put(taskId, new ImportedTask(task, events, lastSequence));
        }
        return tasks;
    }

    private List<Long> readLegacyIndex() {
        File index = new File(legacyDirectory,
                FileTaskStorage.TASK_INDEX_NAME + TASK_FILE_SUFFIX);
        if (!index.isFile()) {
            return List.of();
        }
        return FileUtil.readLines(index, "UTF-8").stream()
                .filter(StringUtils::isNotBlank)
                .map(line -> {
                    try {
                        return Long.valueOf(line.trim());
                    } catch (NumberFormatException e) {
                        log.warn("Skipping unreadable legacy task index entry {}", line);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private Task parseTask(File file) {
        try {
            return JSON.parseObject(FileUtil.readUtf8String(file), Task.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<TaskEvent> readLegacyEvents(Long taskId) {
        File file = new File(legacyDirectory, taskId + FileTaskStorage.TASK_EVENT_FILE_SUFFIX);
        if (!file.isFile()) {
            return List.of();
        }
        return FileUtil.readLines(file, "UTF-8").stream()
                .filter(StringUtils::isNotBlank)
                .map(line -> parseEvent(line, taskId))
                .filter(Objects::nonNull)
                .toList();
    }

    private TaskEvent parseEvent(String line, Long taskId) {
        try {
            TaskEvent event = JSON.parseObject(line, TaskEvent.class);
            if (event == null || event.getSequence() == null || !taskId.equals(event.getTaskId())) {
                log.warn("Skipping invalid legacy task event for task {}", taskId);
                return null;
            }
            return event;
        } catch (RuntimeException e) {
            // A truncated trailing line is expected when the previous process was killed mid-append.
            log.warn("Skipping unreadable legacy task event for task {}", taskId);
            return null;
        }
    }

    private int importTasks(Map<Long, ImportedTask> imported) {
        try (Connection connection = database.open()) {
            try {
                for (ImportedTask entry : imported.values()) {
                    TaskRows.insertTask(connection, entry.task(), entry.lastSequence());
                    for (TaskEvent event : entry.events()) {
                        TaskRows.insertEvent(connection, event);
                    }
                    for (TaskArtifact artifact : migratedArtifacts(entry.task())) {
                        TaskRows.upsertArtifact(connection, entry.task().getId(), artifact);
                    }
                }
                markMigrated(connection, imported.size());
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
            return imported.size();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not import legacy task storage", e);
        }
    }

    /**
     * Tasks created before artifacts were tracked recorded only the legacy single {@code artifactId};
     * write it as the primary output so download and delete see the same file set as for new tasks.
     */
    private static List<TaskArtifact> migratedArtifacts(Task task) {
        List<TaskArtifact> artifacts = new ArrayList<>(
                task.getArtifacts() == null ? List.of() : task.getArtifacts());
        if (StringUtils.isNotBlank(task.getArtifactId()) && artifacts.stream()
                .noneMatch(artifact -> task.getArtifactId().equals(artifact.getArtifactId()))) {
            artifacts.add(TaskArtifact.builder()
                    .artifactId(task.getArtifactId())
                    .role(TaskArtifactRole.OUTPUT)
                    .createdAt(task.getFinishedAt() == null ? task.getUpdatedAt() : task.getFinishedAt())
                    .build());
        }
        return artifacts;
    }

    private void markMigrated(Connection connection, int taskCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "MERGE INTO schema_meta (meta_key, meta_value) KEY(meta_key) VALUES (?, ?)")) {
            statement.setString(1, MIGRATION_MARKER_KEY);
            statement.setString(2, "imported " + taskCount + " tasks at " + System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void renameLegacyDirectory(int taskCount) {
        if (!legacyDirectory.isDirectory()) {
            return;
        }
        File migrated = new File(legacyDirectory.getParentFile(),
                legacyDirectory.getName() + MIGRATED_DIRECTORY_SUFFIX);
        if (migrated.exists()) {
            migrated = new File(legacyDirectory.getParentFile(),
                    legacyDirectory.getName() + MIGRATED_DIRECTORY_SUFFIX + "-" + System.currentTimeMillis());
        }
        try {
            Files.move(legacyDirectory.toPath(), migrated.toPath());
            log.info("Imported {} tasks into H2 task storage and moved the legacy directory to {}",
                    taskCount, migrated);
        } catch (IOException e) {
            // The transaction already committed, so the next start must not import a second time; the
            // directory is left in place and reported instead.
            log.error("Imported {} tasks into H2 task storage but could not move {}; the legacy directory"
                    + " is now unused and must be removed manually", taskCount, legacyDirectory, e);
        }
    }

    private void warnAboutFilesWrittenAfterMigration() {
        if (legacyDirectory.isDirectory()) {
            log.warn("Task storage was already migrated to H2, but {} still exists. Tasks written while"
                    + " the file storage was selected again are not visible.", legacyDirectory);
        }
    }

    private record ImportedTask(Task task, List<TaskEvent> events, long lastSequence) {
    }
}
