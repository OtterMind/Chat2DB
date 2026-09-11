package ai.chat2db.community.domain.api.model.task.extension;

import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;

import java.util.List;
import java.util.Objects;

public final class TaskExecutionContext {

    private final Long taskId;
    private final TaskType taskType;
    private final ConnectionProfile connectionProfile;
    private final String databaseName;
    private final String schemaName;
    private final List<String> tableNames;
    private final TaskOperation operation;

    public TaskExecutionContext(Long taskId, TaskType taskType, ConnectionProfile connectionProfile,
            String databaseName, String schemaName, List<String> tableNames, TaskOperation operation) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskType = Objects.requireNonNull(taskType, "taskType");
        this.connectionProfile = TaskSubmissionContext.copyConnectionProfile(connectionProfile);
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.tableNames = tableNames == null ? List.of() : List.copyOf(tableNames);
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    public Long getTaskId() {
        return taskId;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public ConnectionProfile getConnectionProfile() {
        return TaskSubmissionContext.copyConnectionProfile(connectionProfile);
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public List<String> getTableNames() {
        return tableNames;
    }

    public TaskOperation getOperation() {
        return operation;
    }
}
