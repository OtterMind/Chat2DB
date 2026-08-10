package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.enums.ExportTypeEnum;
import ai.chat2db.community.domain.api.model.db.DbDmlExportPlan;
import ai.chat2db.community.domain.api.model.request.db.DbDmlExportRequest;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.db.IDbDmlExportService;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;

@Component
public class QueryResultExportTaskExecutor implements TaskExecutor<ExportTaskSpec> {

    private final IDbDmlExportService dbDmlExportService;

    public QueryResultExportTaskExecutor(IDbDmlExportService dbDmlExportService) {
        this.dbDmlExportService = dbDmlExportService;
    }

    @Override
    public String taskType() {
        return TaskType.QUERY_RESULT_EXPORT.name();
    }

    @Override
    public Class<ExportTaskSpec> specType() {
        return ExportTaskSpec.class;
    }

    @Override
    public void execute(ExportTaskSpec spec, TaskExecutionContext context) {
        try {
            String format = TaskExecutorSupport.requireFormat(spec.getFormat());
            DbDmlExportRequest request = request(spec, format);
            context.reportProgress(5, TaskStage.QUERYING.name(), "Preparing query export");
            DbDmlExportPlan plan = dbDmlExportService.prepareExport(request);
            String fileName = TaskExecutorSupport.artifactFileName(spec,
                    spec.getSuggestedFileName() == null ? plan.getFileName() : spec.getSuggestedFileName(), format);
            ArtifactDraft draft = context.createArtifact(spec.getExportPath(), fileName,
                    TaskExecutorSupport.mediaType(format));
            ExportProgressLogger progressLogger = new ExportProgressLogger(context, format);
            context.logInfo(TaskEventCode.EXPORT_STARTED.name(), "Query result export started",
                    Map.of(TaskConstants.FILE_FORMAT_DETAIL_KEY, format));
            progressLogger.queryStarted("Reading query result");
            try (OutputStream outputStream = Files.newOutputStream(draft.getTemporaryFile().toPath())) {
                dbDmlExportService.export(plan.getExportRequest(), outputStream, context, context::checkCancelled,
                        progressLogger::recordExportedRows,
                        () -> beginFileFinalization(context, progressLogger, format));
            }
            context.checkCancelled();
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Could not export query result", e);
        }
    }

    private void beginFileFinalization(TaskExecutionContext context, ExportProgressLogger progressLogger,
            String format) {
        progressLogger.queryCompleted("Query result read completed");
        context.reportProgress(90, TaskStage.FINALIZING.name(), "Finalizing " + format + " export file");
        progressLogger.fileFinalizing();
    }

    private DbDmlExportRequest request(ExportTaskSpec spec, String format) {
        DbDmlExportRequest request = new DbDmlExportRequest();
        request.setSql(spec.getSql());
        request.setOriginalSql(spec.getOriginalSql());
        request.setDatabaseName(spec.getTarget().getDatabaseName());
        request.setSchemaName(spec.getTarget().getSchemaName());
        request.setResultSetId(spec.getResultSetId());
        request.setExportSize(spec.getExportSize());
        request.setExportType(switch (TaskFileFormat.valueOf(format)) {
            case CSV -> ExportTypeEnum.CSV.name();
            case XLSX -> ExportTypeEnum.EXCEL.name();
            case XLS -> throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "XLS query export is not supported; use XLSX instead");
            case SQL -> ExportTypeEnum.INSERT.name();
            default -> throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Unsupported query export format");
        });
        return request;
    }
}
