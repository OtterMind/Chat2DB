package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.storage.IdUtil;
import ai.chat2db.community.storage.TaskLifecyclePolicy;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Task storage backed by an embedded H2 file database.
 *
 * <p>Compared with {@code FileTaskStorage} this removes the single instance-wide monitor and the
 * per-event {@code fsync}: task rows are locked one at a time with {@code SELECT ... FOR UPDATE},
 * and durability comes from the transaction commit instead of a forced write per appended event.
 */
@Slf4j
public class H2TaskStorage implements TaskStorage, AutoCloseable {

    private static final String TERMINAL_TASK_FILTER = Arrays.stream(TaskStatus.values())
            .filter(TaskStatus::isTerminal)
            .map(status -> "'" + status.name() + "'")
            .collect(Collectors.joining(", ",
                    " WHERE status IS NOT NULL AND status NOT IN (", ")"));

    private final TaskDatabase database;

    public H2TaskStorage() {
        this(TaskDatabase.defaultStorageBasePath());
    }

    H2TaskStorage(String storageBasePath) {
        this(new TaskDatabase(storageBasePath));
    }

    H2TaskStorage(TaskDatabase database) {
        this.database = database;
        this.database.initialize();
    }

    @Override
    public Task create(Task task, TaskEvent createdEvent) {
        if (task == null || createdEvent == null) {
            throw new IllegalArgumentException("task and createdEvent are required");
        }
        Task stored = copy(task);
        Date now = new Date();
        stored.setStatus(TaskStatus.PENDING.name());
        stored.setProgress(TaskConstants.PENDING_PROGRESS);
        stored.setCreatedAt(now);
        stored.setUpdatedAt(now);
        transact(connection -> {
            Long taskId = nextTaskId(connection);
            stored.setId(taskId);
            TaskRows.insertTask(connection, stored, 1L);
            TaskRows.insertEvent(connection, prepareEvent(createdEvent, taskId, 1L));
            return null;
        });
        copyInto(stored, task);
        return copy(stored);
    }

    @Override
    public Optional<Task> get(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return transact(connection -> {
            Task task = readTask(connection, TaskRows.SELECT_TASK_BY_ID, taskId);
            if (task != null) {
                task.setArtifacts(readArtifacts(connection, taskId));
            }
            return Optional.ofNullable(task);
        });
    }

    @Override
    public PageResponse<Task> list(TaskQuery query) {
        TaskQuery effectiveQuery = query == null ? new TaskQuery() : query;
        int pageNo = Math.max(1, effectiveQuery.getPageNo() == null ? 1 : effectiveQuery.getPageNo());
        int pageSize = Math.max(1, effectiveQuery.getPageSize() == null
                ? TaskConstants.DEFAULT_PAGE_SIZE : effectiveQuery.getPageSize());
        long offset = (long) (pageNo - 1) * pageSize;
        return transact(connection -> {
            long total = countTasks(connection, effectiveQuery);
            List<Task> page = total > offset
                    ? selectTasks(connection, effectiveQuery, offset, pageSize)
                    : List.of();
            return PageResponse.of(List.copyOf(page), total, pageNo, pageSize);
        });
    }

    @Override
    public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
            TaskStatusPatch patch, TaskEvent lifecycleEvent) {
        if (taskId == null) {
            return false;
        }
        return transact(connection -> {
            StoredTask current = readStoredTask(connection, taskId, true);
            if (current == null || !expectedStatus.equals(current.task().getStatus())
                    || !TaskLifecyclePolicy.isLegalTransition(expectedStatus, targetStatus)) {
                return false;
            }
            if (lifecycleEvent == null) {
                throw new IllegalArgumentException("A status transition requires a lifecycle event");
            }
            Task updated = copy(current.task());
            TaskLifecyclePolicy.applyStatusPatch(updated, targetStatus, patch);
            long sequence = current.lastEventSequence() + 1L;
            TaskRows.updateTask(connection, updated, sequence);
            TaskRows.insertEvent(connection, prepareEvent(lifecycleEvent, taskId, sequence));
            return true;
        });
    }

    @Override
    public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
        if (taskId == null || progress == null || progress.getProgress() == null) {
            return false;
        }
        int requested = TaskLifecyclePolicy.runningProgress(progress.getProgress());
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE task SET progress = ?, stage = ?, progress_message = ?, updated_at = ?"
                            + " WHERE id = ? AND status = ? AND progress <= ?")) {
                statement.setInt(1, requested);
                statement.setString(2, progress.getStage());
                statement.setString(3, progress.getMessage());
                statement.setLong(4, System.currentTimeMillis());
                statement.setLong(5, taskId);
                statement.setString(6, TaskStatus.RUNNING.name());
                statement.setInt(7, requested);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public TaskEvent appendEvent(TaskEvent event) {
        if (event == null || event.getTaskId() == null) {
            throw new IllegalArgumentException("event must reference an existing task");
        }
        return transact(connection -> {
            Long sequence = nextEventSequence(connection, event.getTaskId());
            if (sequence == null) {
                throw new IllegalArgumentException("event must reference an existing task");
            }
            TaskEvent prepared = prepareEvent(event, event.getTaskId(), sequence);
            TaskRows.insertEvent(connection, prepared);
            return copyEvent(prepared);
        });
    }

    @Override
    public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
        if (taskId == null) {
            return List.of();
        }
        return transact(connection -> selectEvents(connection,
                "SELECT " + TaskRows.EVENT_COLUMNS + " FROM task_event"
                        + " WHERE task_id = ? AND sequence > ? ORDER BY sequence"
                        + " FETCH FIRST ? ROWS ONLY",
                statement -> {
                    statement.setLong(1, taskId);
                    statement.setLong(2, afterSequence);
                    statement.setInt(3, TaskLifecyclePolicy.eventLimit(limit));
                }));
    }

    @Override
    public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
        if (taskId == null) {
            return List.of();
        }
        return transact(connection -> {
            List<TaskEvent> newestFirst = selectEvents(connection,
                    "SELECT " + TaskRows.EVENT_COLUMNS + " FROM task_event"
                            + " WHERE task_id = ?" + (beforeSequence == null ? "" : " AND sequence < ?")
                            + " ORDER BY sequence DESC FETCH FIRST ? ROWS ONLY",
                    statement -> {
                        statement.setLong(1, taskId);
                        if (beforeSequence == null) {
                            statement.setInt(2, TaskLifecyclePolicy.eventLimit(limit));
                        } else {
                            statement.setLong(2, beforeSequence);
                            statement.setInt(3, TaskLifecyclePolicy.eventLimit(limit));
                        }
                    });
            Collections.reverse(newestFirst);
            return newestFirst;
        });
    }

    @Override
    public List<Task> listNonTerminalTasks() {
        return transact(connection -> selectTasks(connection,
                "SELECT " + TaskRows.TASK_COLUMNS + " FROM task" + TERMINAL_TASK_FILTER + " ORDER BY id"));
    }

    @Override
    public List<Task> listTasksForRecovery() {
        return transact(connection -> selectTasks(connection,
                "SELECT " + TaskRows.TASK_COLUMNS + " FROM task ORDER BY id"));
    }

    @Override
    public boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
        if (taskId == null) {
            return false;
        }
        // The external commit runs before the database commit, so a failure leaves both the task row
        // and its events untouched instead of requiring a compensating restore.
        return transact(connection -> {
            StoredTask current = readStoredTask(connection, taskId, true);
            if (current == null || !TaskStatus.isTerminal(current.task().getStatus())) {
                return false;
            }
            executeUpdate(connection, "DELETE FROM task_event WHERE task_id = ?",
                    statement -> statement.setLong(1, taskId));
            executeUpdate(connection, "DELETE FROM task_artifact WHERE task_id = ?",
                    statement -> statement.setLong(1, taskId));
            executeUpdate(connection, "DELETE FROM resume_state WHERE task_id = ?",
                    statement -> statement.setLong(1, taskId));
            executeUpdate(connection, "DELETE FROM task WHERE id = ?", statement -> statement.setLong(1, taskId));
            if (commitAction != null) {
                commitAction.run();
            }
            return true;
        });
    }

    @Override
    public List<TaskArtifact> listArtifacts(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        return transact(connection -> readArtifacts(connection, taskId));
    }

    @Override
    public void saveArtifact(Long taskId, TaskArtifact artifact) {
        if (taskId == null || artifact == null || artifact.getArtifactId() == null || artifact.getRole() == null) {
            throw new IllegalArgumentException("artifact must reference an existing task");
        }
        transact(connection -> {
            requireTask(connection, taskId, "artifact");
            TaskRows.upsertArtifact(connection, taskId, artifact);
            return null;
        });
    }

    @Override
    public void deleteArtifact(Long taskId, String artifactId) {
        if (taskId == null || artifactId == null) {
            return;
        }
        transact(connection -> executeUpdate(connection,
                "DELETE FROM task_artifact WHERE task_id = ? AND artifact_id = ?", statement -> {
                    statement.setLong(1, taskId);
                    statement.setString(2, artifactId);
                }));
    }

    @Override
    public List<Task> listResumableTasks() {
        return transact(connection -> selectTasks(connection,
                "SELECT " + TaskRows.TASK_COLUMNS + " FROM task" + TERMINAL_TASK_FILTER
                        + " AND EXISTS (SELECT 1 FROM resume_state rs WHERE rs.task_id = task.id) ORDER BY id"));
    }

    @Override
    public void saveResumeState(Long taskId, ResumeState state) {
        if (taskId == null || state == null || state.getShardNo() == null || state.getKind() == null) {
            throw new IllegalArgumentException("resume state must reference an existing task");
        }
        transact(connection -> {
            requireTask(connection, taskId, "resume state");
            try (PreparedStatement statement = connection.prepareStatement(
                    "MERGE INTO resume_state (task_id, shard_no, kind, cursor_json, rows_done, bytes_done, updated_at)"
                            + " KEY(task_id, shard_no) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, taskId);
                statement.setInt(2, state.getShardNo());
                statement.setString(3, state.getKind());
                statement.setString(4, state.getCursorJson());
                setNullableLong(statement, 5, state.getRowsDone());
                setNullableLong(statement, 6, state.getBytesDone());
                statement.setLong(7, (state.getUpdatedAt() == null ? new Date() : state.getUpdatedAt()).getTime());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ResumeState> listResumeStates(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT shard_no, kind, cursor_json, rows_done, bytes_done, updated_at FROM resume_state"
                            + " WHERE task_id = ? ORDER BY shard_no")) {
                statement.setLong(1, taskId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ResumeState> states = new ArrayList<>();
                    while (rows.next()) {
                        states.add(ResumeState.builder()
                                .shardNo(rows.getInt("shard_no"))
                                .kind(rows.getString("kind"))
                                .cursorJson(rows.getString("cursor_json"))
                                .rowsDone(TaskRows.getNullableLong(rows, "rows_done"))
                                .bytesDone(TaskRows.getNullableLong(rows, "bytes_done"))
                                .updatedAt(TaskRows.getNullableDate(rows, "updated_at"))
                                .build());
                    }
                    return states;
                }
            }
        });
    }

    @Override
    public void clearResumeStates(Long taskId) {
        if (taskId == null) {
            return;
        }
        transact(connection -> executeUpdate(connection, "DELETE FROM resume_state WHERE task_id = ?",
                statement -> statement.setLong(1, taskId)));
    }

    public void close() {
        database.close();
    }

    private List<TaskArtifact> readArtifacts(Connection connection, Long taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TaskRows.SELECT_ARTIFACTS)) {
            statement.setLong(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                return TaskRows.readArtifacts(rows);
            }
        }
    }

    private void requireTask(Connection connection, Long taskId, String subject) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM task WHERE id = ?")) {
            statement.setLong(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException(subject + " must reference an existing task");
                }
            }
        }
    }

    private Long nextTaskId(Connection connection) throws SQLException {
        Long taskId;
        do {
            taskId = IdUtil.generateId();
        } while (readTask(connection, TaskRows.SELECT_TASK_BY_ID, taskId) != null);
        return taskId;
    }

    /**
     * Reserves the next event sequence for a task. The update locks the task row until this
     * transaction ends, so concurrent appends cannot be handed the same sequence.
     */
    private Long nextEventSequence(Connection connection, Long taskId) throws SQLException {
        if (executeUpdate(connection, "UPDATE task SET last_event_sequence = last_event_sequence + 1 WHERE id = ?",
                statement -> statement.setLong(1, taskId)) == 0) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_event_sequence FROM task WHERE id = ?")) {
            statement.setLong(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private StoredTask readStoredTask(Connection connection, Long taskId, boolean forUpdate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(forUpdate
                ? TaskRows.SELECT_TASK_BY_ID_FOR_UPDATE : TaskRows.SELECT_TASK_BY_ID)) {
            statement.setLong(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new StoredTask(TaskRows.readTask(rows), TaskRows.lastEventSequence(rows)) : null;
            }
        }
    }

    private Task readTask(Connection connection, String sql, Long taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? TaskRows.readTask(rows) : null;
            }
        }
    }

    private long countTasks(Connection connection, TaskQuery query) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM task" + taskFilterSql(query))) {
            bindFilter(statement, query);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private List<Task> selectTasks(Connection connection, TaskQuery query, long offset, int pageSize)
            throws SQLException {
        return selectTasks(connection, "SELECT " + TaskRows.TASK_COLUMNS + " FROM task" + taskFilterSql(query)
                + " ORDER BY id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", statement -> {
            int index = bindFilter(statement, query);
            statement.setLong(index++, offset);
            statement.setInt(index, pageSize);
        });
    }

    private List<Task> selectTasks(Connection connection, String sql) throws SQLException {
        return selectTasks(connection, sql, statement -> {
        });
    }

    private List<Task> selectTasks(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<Task> tasks = new ArrayList<>();
                while (rows.next()) {
                    tasks.add(TaskRows.readTask(rows));
                }
                return tasks;
            }
        }
    }

    private List<TaskEvent> selectEvents(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<TaskEvent> events = new ArrayList<>();
                while (rows.next()) {
                    events.add(TaskRows.readEvent(rows));
                }
                return events;
            }
        }
    }

    private int bindFilter(PreparedStatement statement, TaskQuery query) throws SQLException {
        int index = 0;
        if (query.getStatus() != null) {
            statement.setString(++index, query.getStatus());
        }
        setNullableLong(statement, ++index, query.getUserId());
        setNullableLong(statement, ++index, query.getOrganizationId());
        return index + 1;
    }

    /**
     * An absent status means "any status", while an absent user or organization means "unscoped", so
     * only the latter two compare against null.
     */
    private String taskFilterSql(TaskQuery query) {
        return query.getStatus() == null
                ? " WHERE user_id IS NOT DISTINCT FROM ? AND organization_id IS NOT DISTINCT FROM ?"
                : " WHERE status = ? AND user_id IS NOT DISTINCT FROM ?"
                        + " AND organization_id IS NOT DISTINCT FROM ?";
    }

    /**
     * {@code IS NOT DISTINCT FROM} needs the parameter type even when the value is null, otherwise
     * H2 cannot infer it from an untyped placeholder.
     */
    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private int executeUpdate(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        }
    }

    private TaskEvent prepareEvent(TaskEvent source, Long taskId, long sequence) {
        TaskEvent event = copyEvent(source);
        event.setTaskId(taskId);
        event.setEventId(event.getEventId() == null ? IdUtil.generateId() : event.getEventId());
        event.setSequence(sequence);
        event.setCreatedAt(event.getCreatedAt() == null ? new Date() : event.getCreatedAt());
        return event;
    }

    private <T> T transact(SqlWork<T> work) {
        try (Connection connection = database.open()) {
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Task storage operation failed", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not access task storage", e);
        }
    }

    private Task copy(Task task) {
        return JSON.parseObject(JSON.toJSONString(task), Task.class);
    }

    private TaskEvent copyEvent(TaskEvent event) {
        return JSON.parseObject(JSON.toJSONString(event), TaskEvent.class);
    }

    private void copyInto(Task source, Task target) {
        Task copy = copy(source);
        target.setId(copy.getId());
        target.setType(copy.getType());
        target.setName(copy.getName());
        target.setStatus(copy.getStatus());
        target.setProgress(copy.getProgress());
        target.setStage(copy.getStage());
        target.setProgressMessage(copy.getProgressMessage());
        target.setTarget(copy.getTarget());
        target.setSpecJson(copy.getSpecJson());
        target.setErrorCode(copy.getErrorCode());
        target.setErrorMessage(copy.getErrorMessage());
        target.setArtifactId(copy.getArtifactId());
        target.setUserId(copy.getUserId());
        target.setOrganizationId(copy.getOrganizationId());
        target.setCreatedAt(copy.getCreatedAt());
        target.setStartedAt(copy.getStartedAt());
        target.setFinishedAt(copy.getFinishedAt());
        target.setUpdatedAt(copy.getUpdatedAt());
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    private record StoredTask(Task task, long lastEventSequence) {
    }
}
