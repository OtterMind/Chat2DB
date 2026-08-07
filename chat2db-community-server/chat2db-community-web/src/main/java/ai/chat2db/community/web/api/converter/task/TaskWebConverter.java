package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.model.request.task.TaskFileImportRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskOtherFileExportRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskSqlFileExportRequest;
import ai.chat2db.community.web.api.model.request.task.OtherFileExportRequest;
import ai.chat2db.community.web.api.model.request.task.OtherFileImportRequest;
import ai.chat2db.community.web.api.model.request.task.SqlFileImportRequest;
import ai.chat2db.community.web.api.model.request.task.SqlFileExportRequest;
import org.springframework.stereotype.Component;

@Component
public class TaskWebConverter {

    public TaskSqlFileExportRequest sqlFileExport2param(SqlFileExportRequest request) {
        if (request == null) {
            return null;
        }
        ExportScopeTypeEnum scope = request.getScope();
        return TaskSqlFileExportRequest.builder()
                .databaseName(request.getDatabaseName())
                .schemaName(request.getSchemaName())
                .tableNames(request.getTableNames())
                .scope(scope == null ? null : scope.name())
                .exportPath(request.getExportPath())
                .containData(request.isContainData())
                .build();
    }

    public TaskOtherFileExportRequest otherFileExport2param(OtherFileExportRequest request) {
        if (request == null) {
            return null;
        }
        return TaskOtherFileExportRequest.builder()
                .databaseName(request.getDatabaseName())
                .schemaName(request.getSchemaName())
                .tableNames(request.getTableNames())
                .exportType(request.getExportType())
                .containsHeader(request.getContainsHeader())
                .exportPath(request.getExportPath())
                .build();
    }

    public TaskFileImportRequest sqlFileImport2param(SqlFileImportRequest request) {
        if (request == null) {
            return null;
        }
        return TaskFileImportRequest.builder()
                .databaseName(request.getDatabaseName())
                .schemaName(request.getSchemaName())
                .fileName(request.getFileName())
                .encoding(request.getEncoding())
                .errorPolicy(request.getErrorPolicy())
                .commitMode(request.getCommitMode())
                .batchSize(request.getBatchSize())
                .build();
    }

    public TaskFileImportRequest otherFileImport2param(OtherFileImportRequest request) {
        if (request == null) {
            return null;
        }
        return TaskFileImportRequest.builder()
                .databaseName(request.getDatabaseName())
                .schemaName(request.getSchemaName())
                .tableName(request.getTableName())
                .importType(request.getImportType())
                .fileName(request.getFileName())
                .encoding(request.getEncoding())
                .errorPolicy(request.getErrorPolicy())
                .commitMode(request.getCommitMode())
                .batchSize(request.getBatchSize())
                .build();
    }
}
