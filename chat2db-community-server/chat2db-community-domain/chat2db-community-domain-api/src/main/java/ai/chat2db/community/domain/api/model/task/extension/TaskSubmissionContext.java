package ai.chat2db.community.domain.api.model.task.extension;

import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;

import java.util.List;
import java.util.Objects;

public final class TaskSubmissionContext {

    private final Long taskId;
    private final TaskType taskType;
    private final ConnectionProfile connectionProfile;
    private final String databaseName;
    private final String schemaName;
    private final List<String> tableNames;
    private final TaskOperation operation;

    public TaskSubmissionContext(Long taskId, TaskType taskType, ConnectionProfile connectionProfile,
            String databaseName, String schemaName, List<String> tableNames, TaskOperation operation) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskType = Objects.requireNonNull(taskType, "taskType");
        this.connectionProfile = copyConnectionProfile(connectionProfile);
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
        return copyConnectionProfile(connectionProfile);
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

    public TaskExecutionContext toExecutionContext() {
        return new TaskExecutionContext(taskId, taskType, connectionProfile, databaseName, schemaName, tableNames,
                operation);
    }

    static ConnectionProfile copyConnectionProfile(ConnectionProfile source) {
        if (source == null) {
            return null;
        }
        ConnectionProfile copy = new ConnectionProfile();
        copy.setDataSourceId(source.getDataSourceId());
        copy.setConsoleId(source.getConsoleId());
        copy.setDatabaseName(source.getDatabaseName());
        copy.setSchemaName(source.getSchemaName());
        copy.setDbType(source.getDbType());
        copy.setAlias(source.getAlias());
        copy.setType(source.getType());
        copy.setUrl(source.getUrl());
        copy.setUser(source.getUser());
        return copy;
    }
}
