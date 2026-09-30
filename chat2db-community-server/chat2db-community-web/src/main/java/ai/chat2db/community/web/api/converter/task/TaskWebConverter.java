package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.enums.ExportSizeEnum;
import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.web.api.model.request.task.TaskExportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportTableSourceRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Locale;

@Component
public class TaskWebConverter {

    public ExportTaskSpec exportRequest2spec(TaskExportRequest request) {
        String taskType = resolveExportTaskType(request);
        String format = normalize(request.getFormat());
        String exportSize = normalize(request.getExportSize());
        if (TaskType.SQL_EXPORT.name().equals(taskType)) {
            format = TaskFileFormat.SQL.name();
        }
        return ExportTaskSpec.builder()
                .taskType(taskType)
                .taskName(StringUtils.defaultIfBlank(request.getTaskName(),
                        defaultExportTaskName(taskType, exportSize, format, request)))
                .target(target(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                        singleTable(request.getTableNames())))
                .tableNames(request.getTableNames())
                .sql(request.getSql())
                .originalSql(request.getOriginalSql())
                .resultSetId(request.getResultSetId())
                .exportSize(exportSize)
                .format(format)
                .scope(normalize(request.getScope()))
                .containData(request.getContainData())
                .containsHeader(request.getContainsHeader())
                .exportPath(request.getExportPath())
                .suggestedFileName(request.getSuggestedFileName())
                .build();
    }

    public ImportTaskSpec importRequest2spec(TaskImportRequest request) {
        String sourceFile = request.getSourceFile();
        String format = normalize(request.getFormat());
        String taskType = resolveImportTaskType(request.getTaskType(), format);
        validateSqlSingleFileRequest(request, taskType);
        String requestedScope = normalize(request.getScope());
        String scope = requestedScope == null ? null : ImportScope.normalize(requestedScope);
        String effectiveScope = ImportScope.normalize(scope);
        List<ImportTableSource> tableSources = importTableSources(request, format);
        String targetTable = StringUtils.defaultIfBlank(request.getTableName(),
                tableSources.size() == 1 ? tableSources.get(0).getTableName() : null);
        return ImportTaskSpec.builder()
                .clientSubmissionId(StringUtils.trimToNull(request.getClientSubmissionId()))
                .taskType(taskType)
                .taskName(StringUtils.defaultIfBlank(request.getTaskName(),
                        defaultImportTaskName(taskType, effectiveScope, request, tableSources)))
                .target(target(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                        targetTable))
                .scope(scope)
                .tableSources(tableSources)
                .logicalDependencies(request.getLogicalDependencies())
                .sourceKind(normalize(request.getSourceKind()))
                .cycleStrategy(normalize(request.getCycleStrategy()))
                .stagingPolicy(request.getStagingPolicy())
                .validationOptions(request.getValidationOptions())
                .finalizationOptions(request.getFinalizationOptions())
                .rollbackOptions(request.getRollbackOptions())
                .performanceSamplePercent(request.getPerformanceSamplePercent())
                .sourceFile(sourceFile)
                .importFileId(request.getFileId())
                .displayFileName(StringUtils.defaultIfBlank(request.getDisplayFileName(), fileName(sourceFile)))
                .format(format)
                .dataTimeFormat(request.getDataTimeFormat())
                .options(request.getOptions())
                .unmappedTarget(request.getUnmappedTarget())
                .mode(normalize(request.getMode()))
                .confirmedNoStrongRelations(request.getConfirmedNoStrongRelations())
                .build();
    }

    private void validateSqlSingleFileRequest(TaskImportRequest request, String taskType) {
        if (!TaskType.SQL_FILE_IMPORT.name().equals(taskType)) {
            return;
        }
        if (CollectionUtils.isNotEmpty(request.getTableSources())
                || CollectionUtils.isNotEmpty(request.getLogicalDependencies())
                || StringUtils.isNotBlank(request.getCycleStrategy())
                || request.getStagingPolicy() != null
                || request.getValidationOptions() != null
                || request.getFinalizationOptions() != null
                || request.getRollbackOptions() != null
                || request.getPerformanceSamplePercent() != null
                || request.getConfirmedNoStrongRelations() != null) {
            throw new IllegalArgumentException(
                    "SQL file import does not accept multi-table manifest controls");
        }
    }

    private List<ImportTableSource> importTableSources(TaskImportRequest request, String defaultFormat) {
        if (CollectionUtils.isEmpty(request.getTableSources())) {
            return List.of();
        }
        return request.getTableSources().stream().map(source -> importTableSource(request, source, defaultFormat))
                .toList();
    }

    private ImportTableSource importTableSource(TaskImportRequest request, TaskImportTableSourceRequest source,
            String defaultFormat) {
        if (source == null) {
            throw new IllegalArgumentException("Import table source cannot be null");
        }
        String sourceFile = source.getSourceFile();
        return ImportTableSource.builder()
                .databaseName(StringUtils.defaultIfBlank(source.getDatabaseName(), request.getDatabaseName()))
                .schemaName(StringUtils.defaultIfBlank(source.getSchemaName(), request.getSchemaName()))
                .tableName(source.getTableName())
                .sourceFile(sourceFile)
                .importFileId(source.getFileId())
                .displayFileName(StringUtils.defaultIfBlank(source.getDisplayFileName(), fileName(sourceFile)))
                .format(StringUtils.defaultIfBlank(normalize(source.getFormat()), defaultFormat))
                .dataTimeFormat(StringUtils.defaultIfBlank(source.getDataTimeFormat(), request.getDataTimeFormat()))
                .columnMappings(source.getColumnMappings())
                .unmappedTarget(source.getUnmappedTarget() == null
                        ? request.getUnmappedTarget() : source.getUnmappedTarget())
                .options(source.getOptions() == null ? request.getOptions() : source.getOptions())
                .build();
    }

    private String resolveExportTaskType(TaskExportRequest request) {
        if (StringUtils.isNotBlank(request.getTaskType())) {
            TaskType taskType = TaskType.valueOf(normalize(request.getTaskType()));
            if (taskType != TaskType.QUERY_RESULT_EXPORT && taskType != TaskType.SQL_EXPORT
                    && taskType != TaskType.TABLE_DATA_EXPORT) {
                throw new IllegalArgumentException("Unsupported export task type: " + taskType);
            }
            return taskType.name();
        }
        if (StringUtils.isNotBlank(request.getSql()) || StringUtils.isNotBlank(request.getOriginalSql())) {
            return TaskType.QUERY_RESULT_EXPORT.name();
        }
        if (StringUtils.isNotBlank(request.getScope())) {
            return TaskType.SQL_EXPORT.name();
        }
        return TaskType.TABLE_DATA_EXPORT.name();
    }

    private String resolveImportTaskType(String requestedTaskType, String format) {
        if (StringUtils.isNotBlank(requestedTaskType)) {
            TaskType taskType = TaskType.valueOf(normalize(requestedTaskType));
            if (taskType != TaskType.DATA_FILE_IMPORT && taskType != TaskType.SQL_FILE_IMPORT) {
                throw new IllegalArgumentException("Unsupported import task type: " + taskType);
            }
            return taskType.name();
        }
        return TaskFileFormat.SQL.name().equals(format)
                ? TaskType.SQL_FILE_IMPORT.name() : TaskType.DATA_FILE_IMPORT.name();
    }

    private TaskTargetSnapshot target(Long dataSourceId, String databaseName, String schemaName, String tableName) {
        return TaskTargetSnapshot.builder()
                .dataSourceId(dataSourceId)
                .databaseName(databaseName)
                .schemaName(schemaName)
                .tableName(tableName)
                .build();
    }

    private String singleTable(List<String> tableNames) {
        return tableNames != null && tableNames.size() == 1 ? tableNames.get(0) : null;
    }

    private String defaultExportTaskName(String taskType, String exportSize, String format, TaskExportRequest request) {
        if (TaskType.QUERY_RESULT_EXPORT.name().equals(taskType)) {
            return queryResultExportOperation(exportSize, format) + " - "
                    + resultTargetName(request.getDatabaseName(), request.getSchemaName(), request.getTableNames());
        }
        if (TaskType.SQL_EXPORT.name().equals(taskType)) {
            return sqlExportOperation(normalize(request.getScope())) + " - "
                    + qualifiedTargetName(request.getDatabaseName(), request.getSchemaName(), request.getTableNames());
        }
        return "Export table data - " + qualifiedTargetName(request.getDatabaseName(), request.getSchemaName(),
                request.getTableNames());
    }

    private String sqlExportOperation(String scope) {
        if (ExportScopeTypeEnum.SCHEMA.name().equals(scope)) {
            return "Export database structure";
        }
        if (ExportScopeTypeEnum.TABLE.name().equals(scope)) {
            return "Export database data";
        }
        if (ExportScopeTypeEnum.ALL.name().equals(scope)) {
            return "Export database structure and data";
        }
        return "Export database SQL";
    }

    private String queryResultExportOperation(String exportSize, String format) {
        String resultScope;
        if (ExportSizeEnum.ALL.name().equals(exportSize)) {
            resultScope = "all query results";
        } else if (ExportSizeEnum.CURRENT_PAGE.name().equals(exportSize)) {
            resultScope = "current page results";
        } else {
            resultScope = "query result";
        }

        String displayFormat = TaskFileFormat.SQL.name().equals(format) ? "INSERT SQL" : format;
        return "Export " + resultScope
                + (StringUtils.isBlank(displayFormat) ? "" : " as " + displayFormat);
    }

    private String defaultImportTaskName(String taskType, String scope, TaskImportRequest request,
            List<ImportTableSource> tableSources) {
        String operation = TaskType.SQL_FILE_IMPORT.name().equals(taskType)
                ? "Import SQL file" : ImportScope.DATABASE.equals(scope)
                ? "Import database data" : ImportScope.SCHEMA.equals(scope)
                ? "Import schema data" : "Import table data";
        List<String> tableNames = CollectionUtils.isNotEmpty(tableSources)
                ? tableSources.stream().map(ImportTableSource::getTableName)
                        .filter(StringUtils::isNotBlank).toList()
                : StringUtils.isBlank(request.getTableName()) ? List.of() : List.of(request.getTableName());
        return operation + " - " + qualifiedTargetName(request.getDatabaseName(), request.getSchemaName(), tableNames);
    }

    private String resultTargetName(String databaseName, String schemaName, List<String> tableNames) {
        if (CollectionUtils.isNotEmpty(tableNames)) {
            return tableListName(tableNames);
        }
        return namespaceName(databaseName, schemaName);
    }

    private String qualifiedTargetName(String databaseName, String schemaName, List<String> tableNames) {
        String namespace = namespaceName(databaseName, schemaName);
        if (CollectionUtils.isEmpty(tableNames)) {
            return namespace;
        }
        return namespace + "." + tableListName(tableNames);
    }

    private String namespaceName(String databaseName, String schemaName) {
        if (StringUtils.isNotBlank(databaseName) && StringUtils.isNotBlank(schemaName)
                && !databaseName.equals(schemaName)) {
            return databaseName + "." + schemaName;
        }
        return StringUtils.firstNonBlank(schemaName, databaseName, "chat2db");
    }

    private String tableListName(List<String> tableNames) {
        if (tableNames.size() <= 2) {
            return String.join(", ", tableNames);
        }
        return tableNames.get(0) + ", " + tableNames.get(1) + " +" + (tableNames.size() - 2);
    }

    private String normalize(String value) {
        return StringUtils.isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String fileName(String path) {
        return StringUtils.isBlank(path) ? null : new File(path).getName();
    }

}
