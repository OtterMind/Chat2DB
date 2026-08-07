package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionResult;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SqlExportTaskExecutor implements TaskExecutor<ExportTaskSpec> {

    @Override
    public String taskType() {
        return TaskType.SQL_EXPORT.name();
    }

    @Override
    public Class<ExportTaskSpec> specType() {
        return ExportTaskSpec.class;
    }

    @Override
    public TaskExecutionResult execute(ExportTaskSpec spec, TaskExecutionContext context) {
        try {
            ExportScopeTypeEnum scope = ExportScopeTypeEnum.from(spec.getScope());
            if (scope == null) {
                throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(), "Export scope is required");
            }
            String format = TaskFileFormat.SQL.name();
            String fileName = TaskExecutorSupport.artifactFileName(spec, spec.getSuggestedFileName(), format);
            ArtifactDraft draft = context.createArtifact(spec.getExportPath(), fileName,
                    TaskExecutorSupport.mediaType(format));
            Files.createFile(draft.getTemporaryFile().toPath());
            Connection connection = TaskStatementTrackingConnection.wrap(Chat2DBContext.getConnection(), context);
            Map<String, Object> exportDetails = exportDetails(spec, scope, format);
            context.logInfo(TaskEventCode.EXPORT_STARTED.name(), "Database SQL export started", exportDetails);
            context.reportProgress(10, TaskStage.EXPORTING.name(), "Exporting database SQL");
            if (scope == ExportScopeTypeEnum.TABLE) {
                exportTableData(spec, context, connection);
            } else {
                exportStructure(spec, context, connection, scope == ExportScopeTypeEnum.ALL);
            }
            context.checkCancelled();
            context.reportProgress(92, TaskStage.FINALIZING.name(), "Finalizing SQL export file");
            context.logInfo(TaskEventCode.FILE_FINALIZING.name(), "Finalizing SQL export file", exportDetails);
            return TaskExecutionResult.withArtifact(draft);
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Could not export database SQL", e);
        }
    }

    private void exportStructure(ExportTaskSpec spec, TaskExecutionContext context, Connection connection,
            boolean containData) throws Exception {
        List<String> tables = spec.getTableNames();
        if (CollectionUtils.isEmpty(tables)) {
            Chat2DBContext.getDbManager().exportDatabase(connection,
                    spec.getTarget().getDatabaseName(), spec.getTarget().getSchemaName(),
                    containData, context);
            return;
        }
        exportTables(spec, context, connection, tables, false, containData);
    }

    private void exportTableData(ExportTaskSpec spec, TaskExecutionContext context, Connection connection)
            throws Exception {
        List<String> tables = spec.getTableNames();
        if (CollectionUtils.isEmpty(tables)) {
            List<Table> tableList = Chat2DBContext.getDbMetaData().tables(connection,
                    new TablesRequest(spec.getTarget().getDatabaseName(), spec.getTarget().getSchemaName(), null));
            tables = CollectionUtils.isEmpty(tableList)
                    ? new ArrayList<>() : tableList.stream().map(Table::getName).toList();
        }
        exportTables(spec, context, connection, tables, true, false);
    }

    private void exportTables(ExportTaskSpec spec, TaskExecutionContext context, Connection connection,
            List<String> tables, boolean dataOnly, boolean containData) throws Exception {
        int total = tables.size();
        for (int index = 0; index < total; index++) {
            context.checkCancelled();
            String table = tables.get(index);
            context.logInfo(TaskEventCode.TABLE_EXPORT_STARTED.name(),
                    "Exporting table " + (index + 1) + "/" + total + ": " + table,
                    tableDetails(table, index, total));
            if (dataOnly) {
                Chat2DBContext.getDbManager().exportTableData(connection,
                        spec.getTarget().getDatabaseName(), spec.getTarget().getSchemaName(), table, context);
            } else {
                Chat2DBContext.getDbManager().exportTable(connection,
                        spec.getTarget().getDatabaseName(), spec.getTarget().getSchemaName(), table,
                        containData, context);
            }
            context.logInfo(TaskEventCode.TABLE_EXPORT_COMPLETED.name(),
                    "Table export completed " + (index + 1) + "/" + total + ": " + table,
                    tableDetails(table, index, total));
            context.reportProgress(Math.min(90, 10 + ((index + 1) * 80 / Math.max(1, total))),
                    TaskStage.EXPORTING.name(), "Exported " + (index + 1) + " of " + total + " tables");
        }
    }

    private Map<String, Object> exportDetails(ExportTaskSpec spec, ExportScopeTypeEnum scope, String format) {
        return Map.of(TaskConstants.FILE_FORMAT_DETAIL_KEY, format,
                TaskConstants.EXPORT_SCOPE_DETAIL_KEY, scope.name(),
                TaskConstants.TOTAL_TABLES_DETAIL_KEY, CollectionUtils.size(spec.getTableNames()));
    }

    private Map<String, Object> tableDetails(String tableName, int tableIndex, int totalTables) {
        return Map.of(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName,
                TaskConstants.EXPORTED_TABLES_DETAIL_KEY, tableIndex + 1,
                TaskConstants.TOTAL_TABLES_DETAIL_KEY, totalTables);
    }
}
