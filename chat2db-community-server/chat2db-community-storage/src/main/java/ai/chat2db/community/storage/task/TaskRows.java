package ai.chat2db.community.storage.task;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Row mapping shared by {@link H2TaskStorage} and {@link TaskStorageMigrator} so both write the
 * same representation of a task.
 */
final class TaskRows {

    static final String TASK_COLUMNS = "id, type, name, status, progress, stage, progress_message,"
            + " error_code, error_message, artifact_id, target_json, spec_json, user_id, organization_id,"
            + " created_at, started_at, finished_at, updated_at, last_event_sequence";

    static final String EVENT_COLUMNS = "task_id, sequence, event_id, level, code, stage, message,"
            + " details, created_at";

    static final String INSERT_TASK = "INSERT INTO task (" + TASK_COLUMNS + ") VALUES ("
            + placeholders(19) + ")";

    static final String UPDATE_TASK = "UPDATE task SET type = ?, name = ?, status = ?, progress = ?,"
            + " stage = ?, progress_message = ?, error_code = ?, error_message = ?, artifact_id = ?,"
            + " target_json = ?, spec_json = ?, user_id = ?, organization_id = ?, created_at = ?,"
            + " started_at = ?, finished_at = ?, updated_at = ?, last_event_sequence = ? WHERE id = ?";

    static final String INSERT_EVENT = "INSERT INTO task_event (" + EVENT_COLUMNS + ") VALUES ("
            + placeholders(9) + ")";

    static final String SELECT_TASK_BY_ID = "SELECT " + TASK_COLUMNS + " FROM task WHERE id = ?";

    static final String SELECT_TASK_BY_ID_FOR_UPDATE = SELECT_TASK_BY_ID + " FOR UPDATE";

    static final String UPSERT_ARTIFACT = "MERGE INTO task_artifact"
            + " (task_id, artifact_id, role, media_type, size_bytes, created_at)"
            + " KEY(task_id, artifact_id) VALUES (?, ?, ?, ?, ?, ?)";

    static final String SELECT_ARTIFACTS = "SELECT artifact_id, role, media_type, size_bytes, created_at"
            + " FROM task_artifact WHERE task_id = ? ORDER BY created_at, artifact_id";

    private TaskRows() {
    }

    static void upsertArtifact(Connection connection, Long taskId, TaskArtifact artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_ARTIFACT)) {
            statement.setLong(1, taskId);
            statement.setString(2, artifact.getArtifactId());
            statement.setString(3, artifact.getRole());
            setString(statement, 4, artifact.getMediaType());
            setLong(statement, 5, artifact.getSizeBytes());
            setDate(statement, 6, artifact.getCreatedAt() == null ? new Date() : artifact.getCreatedAt());
            statement.executeUpdate();
        }
    }

    static void insertTask(Connection connection, Task task, long lastEventSequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TASK)) {
            bindTask(statement, task, lastEventSequence, false);
            statement.executeUpdate();
        }
    }

    static void updateTask(Connection connection, Task task, long lastEventSequence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_TASK)) {
            bindTask(statement, task, lastEventSequence, true);
            statement.executeUpdate();
        }
    }

    static void insertEvent(Connection connection, TaskEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
            bindEvent(statement, event);
            statement.executeUpdate();
        }
    }

    static Task readTask(ResultSet rows) throws SQLException {
        String targetJson = rows.getString("target_json");
        return Task.builder()
                .id(rows.getLong("id"))
                .type(rows.getString("type"))
                .name(rows.getString("name"))
                .status(rows.getString("status"))
                .progress(rows.getInt("progress"))
                .stage(rows.getString("stage"))
                .progressMessage(rows.getString("progress_message"))
                .target(StringUtils.isBlank(targetJson) ? null
                        : JSON.parseObject(targetJson, TaskTargetSnapshot.class))
                .specJson(rows.getString("spec_json"))
                .errorCode(rows.getString("error_code"))
                .errorMessage(rows.getString("error_message"))
                .artifactId(rows.getString("artifact_id"))
                .userId(getNullableLong(rows, "user_id"))
                .organizationId(getNullableLong(rows, "organization_id"))
                .createdAt(getNullableDate(rows, "created_at"))
                .startedAt(getNullableDate(rows, "started_at"))
                .finishedAt(getNullableDate(rows, "finished_at"))
                .updatedAt(getNullableDate(rows, "updated_at"))
                .build();
    }

    static TaskEvent readEvent(ResultSet rows) throws SQLException {
        String detailsJson = rows.getString("details");
        return TaskEvent.builder()
                .eventId(getNullableLong(rows, "event_id"))
                .taskId(getNullableLong(rows, "task_id"))
                .sequence(rows.getLong("sequence"))
                .level(rows.getString("level"))
                .code(rows.getString("code"))
                .stage(rows.getString("stage"))
                .message(rows.getString("message"))
                .details(StringUtils.isBlank(detailsJson) ? null : JSON.parseObject(detailsJson))
                .createdAt(getNullableDate(rows, "created_at"))
                .build();
    }

    static long lastEventSequence(ResultSet rows) throws SQLException {
        return rows.getLong("last_event_sequence");
    }

    static List<TaskArtifact> readArtifacts(ResultSet rows) throws SQLException {
        List<TaskArtifact> artifacts = new ArrayList<>();
        while (rows.next()) {
            artifacts.add(TaskArtifact.builder()
                    .artifactId(rows.getString("artifact_id"))
                    .role(rows.getString("role"))
                    .mediaType(rows.getString("media_type"))
                    .sizeBytes(getNullableLong(rows, "size_bytes"))
                    .createdAt(getNullableDate(rows, "created_at"))
                    .build());
        }
        return artifacts;
    }

    private static void bindTask(PreparedStatement statement, Task task, long lastEventSequence,
            boolean forUpdate) throws SQLException {
        int index = 1;
        if (!forUpdate) {
            statement.setLong(index++, task.getId());
        }
        setString(statement, index++, task.getType());
        setString(statement, index++, task.getName());
        statement.setString(index++, task.getStatus());
        statement.setInt(index++, task.getProgress() == null ? 0 : task.getProgress());
        setString(statement, index++, task.getStage());
        setString(statement, index++, task.getProgressMessage());
        setString(statement, index++, task.getErrorCode());
        setString(statement, index++, task.getErrorMessage());
        setString(statement, index++, task.getArtifactId());
        setString(statement, index++, task.getTarget() == null ? null : JSON.toJSONString(task.getTarget()));
        setString(statement, index++, task.getSpecJson());
        setLong(statement, index++, task.getUserId());
        setLong(statement, index++, task.getOrganizationId());
        index = setDate(statement, index, task.getCreatedAt());
        index = setDate(statement, index, task.getStartedAt());
        index = setDate(statement, index, task.getFinishedAt());
        index = setDate(statement, index, task.getUpdatedAt());
        statement.setLong(index++, lastEventSequence);
        if (forUpdate) {
            statement.setLong(index, task.getId());
        }
    }

    private static void bindEvent(PreparedStatement statement, TaskEvent event) throws SQLException {
        int index = 1;
        statement.setLong(index++, event.getTaskId());
        statement.setLong(index++, event.getSequence());
        setLong(statement, index++, event.getEventId());
        setString(statement, index++, event.getLevel());
        setString(statement, index++, event.getCode());
        setString(statement, index++, event.getStage());
        setString(statement, index++, event.getMessage());
        setString(statement, index++, event.getDetails() == null ? null : JSON.toJSONString(event.getDetails()));
        setDate(statement, index, event.getCreatedAt());
    }

    private static void setString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static int setDate(PreparedStatement statement, int index, Date value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value.getTime());
        }
        return index + 1;
    }

    static Long getNullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    static Date getNullableDate(ResultSet rows, String column) throws SQLException {
        Long value = getNullableLong(rows, column);
        return value == null ? null : new Date(value);
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) {
            builder.append(index == 0 ? "?" : ", ?");
        }
        return builder.toString();
    }
}
