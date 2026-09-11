package ai.chat2db.community.domain.api.model.task.extension;

import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class TaskStatementContext {

    private final TaskExecutionContext taskContext;
    private final String sql;
    private final String sqlDigest;

    public TaskStatementContext(TaskExecutionContext taskContext, String sql) {
        this.taskContext = Objects.requireNonNull(taskContext, "taskContext");
        this.sql = Objects.requireNonNull(sql, "sql");
        this.sqlDigest = sha256(sql);
    }

    public TaskExecutionContext getTaskContext() {
        return taskContext;
    }

    public String getSql() {
        return sql;
    }

    public String getSqlDigest() {
        return sqlDigest;
    }

    public Long getTaskId() {
        return taskContext.getTaskId();
    }

    public TaskType getTaskType() {
        return taskContext.getTaskType();
    }

    public ConnectionProfile getConnectionProfile() {
        return taskContext.getConnectionProfile();
    }

    public String getDatabaseName() {
        return taskContext.getDatabaseName();
    }

    public String getSchemaName() {
        return taskContext.getSchemaName();
    }

    public List<String> getTableNames() {
        return taskContext.getTableNames();
    }

    public TaskOperation getOperation() {
        return taskContext.getOperation();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
