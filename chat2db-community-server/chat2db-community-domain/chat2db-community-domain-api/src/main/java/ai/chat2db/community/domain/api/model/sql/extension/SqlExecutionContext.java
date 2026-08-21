package ai.chat2db.community.domain.api.model.sql.extension;

import java.util.Objects;

public final class SqlExecutionContext {

    private final Long dataSourceId;
    private final String dbType;
    private final String databaseName;
    private final String schemaName;
    private final String tableName;
    private final String originalSql;
    private final SqlExecutionOperation operation;
    private final String exportType;
    private final Long applyId;

    public SqlExecutionContext(Long dataSourceId, String dbType, String databaseName, String schemaName,
            String tableName, String originalSql, SqlExecutionOperation operation, String exportType) {
        this(dataSourceId, dbType, databaseName, schemaName, tableName, originalSql, operation, exportType, null);
    }

    public SqlExecutionContext(Long dataSourceId, String dbType, String databaseName, String schemaName,
            String tableName, String originalSql, SqlExecutionOperation operation, String exportType, Long applyId) {
        this.dataSourceId = dataSourceId;
        this.dbType = dbType;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.originalSql = Objects.requireNonNull(originalSql, "originalSql");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.exportType = exportType;
        this.applyId = applyId;
    }

    public Long getDataSourceId() {
        return dataSourceId;
    }

    public String getDbType() {
        return dbType;
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

    public String getOriginalSql() {
        return originalSql;
    }

    public SqlExecutionOperation getOperation() {
        return operation;
    }

    public String getExportType() {
        return exportType;
    }

    public Long getApplyId() {
        return applyId;
    }
}
