package ai.chat2db.community.domain.api.model.task.extension;

public final class ExportCellContext {

    private final Long dataSourceId;
    private final String dbType;
    private final String databaseName;
    private final String schemaName;
    private final String tableName;
    private final String columnName;
    private final String exportType;

    public ExportCellContext(Long dataSourceId, String dbType, String databaseName, String schemaName,
            String tableName, String columnName, String exportType) {
        this.dataSourceId = dataSourceId;
        this.dbType = dbType;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.columnName = columnName;
        this.exportType = exportType;
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

    public String getColumnName() {
        return columnName;
    }

    public String getExportType() {
        return exportType;
    }
}
