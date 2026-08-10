package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.enums.ExportSizeEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.web.api.model.request.task.TaskExportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
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
        return ImportTaskSpec.builder()
                .taskType(taskType)
                .taskName(StringUtils.defaultIfBlank(request.getTaskName(),
                        defaultImportTaskName(taskType, request)))
                .target(target(request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(),
                        request.getTableName()))
                .sourceFile(sourceFile)
                .displayFileName(StringUtils.defaultIfBlank(request.getDisplayFileName(), fileName(sourceFile)))
                .format(format)
                .dataTimeFormat(request.getDataTimeFormat())
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
            return "Export database SQL - " + qualifiedTargetName(request.getDatabaseName(), request.getSchemaName(),
                    request.getTableNames());
        }
        return "Export table data - " + qualifiedTargetName(request.getDatabaseName(), request.getSchemaName(),
                request.getTableNames());
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

    private String defaultImportTaskName(String taskType, TaskImportRequest request) {
        String operation = TaskType.SQL_FILE_IMPORT.name().equals(taskType)
                ? "Import SQL file" : "Import table data";
        List<String> tableNames = StringUtils.isBlank(request.getTableName())
                ? List.of() : List.of(request.getTableName());
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
