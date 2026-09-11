package ai.chat2db.community.domain.api.model.sql.extension;

import java.util.Objects;

public final class SqlResultColumnContext {

    private final SqlExecutionPlan executionPlan;
    private final int columnIndex;
    private final String columnName;
    private final String columnLabel;
    private final Integer jdbcType;
    private final String typeName;
    private final String databaseName;
    private final String schemaName;
    private final String tableName;
    private final boolean synthetic;

    public SqlResultColumnContext(SqlExecutionPlan executionPlan, int columnIndex, String columnName,
            String columnLabel, Integer jdbcType, String typeName, String databaseName, String schemaName,
            String tableName, boolean synthetic) {
        this.executionPlan = Objects.requireNonNull(executionPlan, "executionPlan");
        if (columnIndex < 1) {
            throw new IllegalArgumentException("columnIndex must be greater than zero");
        }
        this.columnIndex = columnIndex;
        this.columnName = columnName;
        this.columnLabel = columnLabel;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.synthetic = synthetic;
    }

    public SqlExecutionPlan getExecutionPlan() {
        return executionPlan;
    }

    public int getColumnIndex() {
        return columnIndex;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getColumnLabel() {
        return columnLabel;
    }

    public Integer getJdbcType() {
        return jdbcType;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public boolean isSynthetic() {
        return synthetic;
    }
}
