package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.enums.TaskTypeEnum;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.domain.api.model.request.task.TaskFileImportRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskOtherFileExportRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordCreateRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskSqlFileExportRequest;
import ai.chat2db.community.domain.api.model.task.ExportAsyncContext;
import ai.chat2db.community.domain.api.model.task.ImportAsyncContext;
import ai.chat2db.community.domain.api.service.task.ITaskDataExportService;
import ai.chat2db.community.domain.api.service.task.ITaskDataImportService;
import ai.chat2db.community.domain.api.service.task.ITaskExecutionService;
import ai.chat2db.community.domain.api.service.task.ITaskExportService;
import ai.chat2db.community.domain.api.service.task.ITaskFileService;
import ai.chat2db.community.domain.api.service.task.ITaskRecordService;
import ai.chat2db.community.domain.api.service.task.ITaskSchedulerService;
import ai.chat2db.community.domain.api.service.task.ITaskTransferService;
import ai.chat2db.community.tools.util.ContextUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskTransferServiceImpl implements ITaskTransferService {

    private final ITaskExecutionService taskExecutionService;
    private final ITaskExportService taskExportService;
    private final ITaskDataExportService taskDataExportService;
    private final ITaskDataImportService taskDataImportService;
    private final ITaskRecordService taskRecordService;
    private final ITaskFileService taskFileService;
    private final ITaskSchedulerService taskSchedulerService;

    public TaskTransferServiceImpl(ITaskExecutionService taskExecutionService,
            ITaskExportService taskExportService,
            ITaskDataExportService taskDataExportService,
            ITaskDataImportService taskDataImportService,
            ITaskRecordService taskRecordService,
            ITaskFileService taskFileService,
            ITaskSchedulerService taskSchedulerService) {
        this.taskExecutionService = taskExecutionService;
        this.taskExportService = taskExportService;
        this.taskDataExportService = taskDataExportService;
        this.taskDataImportService = taskDataImportService;
        this.taskRecordService = taskRecordService;
        this.taskFileService = taskFileService;
        this.taskSchedulerService = taskSchedulerService;
    }

    @Override
    public Long exportSqlFile(TaskSqlFileExportRequest request) {
        if (StringUtils.isBlank(request.getExportPath())) {
            request.setExportPath(taskFileService.defaultExportPath());
        }
        Long taskId = createExportTask(request.getDatabaseName(), request.getSchemaName(), request.getTableNames());
        ExportScopeTypeEnum scope = ExportScopeTypeEnum.valueOf(request.getScope());
        switch (scope) {
            case ALL:
                request.setContainData(Boolean.TRUE);
                submitSqlExport(taskId, request);
                break;
            case SCHEMA:
            case TABLE:
                request.setContainData(Boolean.FALSE);
                submitSqlExport(taskId, request);
                break;
            default:
                throw new IllegalArgumentException("Unsupported export scope: " + request.getScope());
        }
        return taskId;
    }

    @Override
    public Long exportOtherFile(TaskOtherFileExportRequest request) {
        if (StringUtils.isBlank(request.getExportPath())) {
            request.setExportPath(taskFileService.defaultExportPath());
        }
        Long taskId = createExportTask(request.getDatabaseName(), request.getSchemaName(), request.getTableNames());
        if (CollectionUtils.isEmpty(request.getTableNames())) {
            throw new IllegalArgumentException("Export table names must not be empty");
        }
        ExportAsyncContext asyncContext = taskFileService.createOtherExportContext(taskId, request.getExportPath(),
                request.getDatabaseName(), request.getSchemaName(), request.getTableNames(), request.getExportType(),
                request.getContainsHeader(), taskSchedulerService.asyncCall(taskId), ContextUtils.queryContext());
        taskSchedulerService.submit(taskId, asyncContext,
                taskExecutionService.withCurrentConnectionContext(ContextUtils.queryContext(),
                        () -> taskDataExportService.exportOtherFile(asyncContext)));
        return taskId;
    }

    @Override
    public Long importSqlFile(TaskFileImportRequest request) {
        Long taskId = createImportTask(request.getDatabaseName(), request.getSchemaName(), request.getTableName());
        ImportAsyncContext asyncContext = createImportContext(taskId, "SQL", request);
        taskSchedulerService.submit(taskId, asyncContext,
                taskExecutionService.withCurrentConnectionContext(ContextUtils.queryContext(),
                        () -> taskDataImportService.importOtherFile(asyncContext)));
        return taskId;
    }

    @Override
    public Long importOtherFile(TaskFileImportRequest request) {
        Long taskId = createImportTask(request.getDatabaseName(), request.getSchemaName(), request.getTableName());
        ImportAsyncContext asyncContext = createImportContext(taskId, request.getImportType(), request);
        taskSchedulerService.submit(taskId, asyncContext,
                taskExecutionService.withCurrentConnectionContext(ContextUtils.queryContext(),
                        () -> taskDataImportService.importOtherFile(asyncContext)));
        return taskId;
    }

    private void submitSqlExport(Long taskId, TaskSqlFileExportRequest request) {
        AsyncContext asyncContext = taskFileService.createSqlExportContext(taskId, request.getExportPath(),
                request.getDatabaseName(), request.getSchemaName(), request.getTableNames(), request.isContainData(),
                taskSchedulerService.asyncCall(taskId), ContextUtils.queryContext());
        taskSchedulerService.submit(taskId, asyncContext,
                taskExecutionService.withCurrentConnectionContext(ContextUtils.queryContext(),
                        () -> taskExportService.exportSqlFile(request, asyncContext)));
    }

    private ImportAsyncContext createImportContext(Long taskId, String importType, TaskFileImportRequest request) {
        ImportAsyncContext context = taskFileService.createImportContext(taskId, importType, request.getTableName(),
                request.getFileName(), taskSchedulerService.asyncCall(taskId), ContextUtils.queryContext());
        context.setEncoding(request.getEncoding());
        context.setErrorPolicy(request.getErrorPolicy());
        context.setCommitMode(request.getCommitMode());
        context.setBatchSize(request.getBatchSize());
        return context;
    }

    private Long createExportTask(String databaseName, String schemaName, List<String> tableNames) {
        String taskName = taskFileService.resolveTaskName(databaseName, schemaName, tableNames);
        TaskRecordCreateRequest request = baseTaskCreateRequest("Export " + taskName,
                TaskTypeEnum.DOWNLOAD_TABLE_STRUCTURE.name(), databaseName, schemaName);
        if (CollectionUtils.isNotEmpty(tableNames)) {
            request.setTableName(String.join(",", tableNames));
        }
        return createTask(request);
    }

    private Long createImportTask(String databaseName, String schemaName, String tableName) {
        String taskName = StringUtils.defaultIfBlank(StringUtils.defaultIfBlank(schemaName, databaseName), tableName);
        TaskRecordCreateRequest request = baseTaskCreateRequest("Import " + taskName,
                TaskTypeEnum.UPLOAD_TABLE_DATA.name(), databaseName, schemaName);
        if (StringUtils.isNotEmpty(tableName)) {
            request.setTableName(tableName);
        }
        return createTask(request);
    }

    private TaskRecordCreateRequest baseTaskCreateRequest(String taskName, String taskType, String databaseName,
            String schemaName) {
        TaskRecordCreateRequest request = new TaskRecordCreateRequest();
        request.setTaskName(taskName);
        request.setTaskType(taskType);
        request.setDatabaseName(databaseName);
        request.setSchemaName(schemaName);
        return request;
    }

    private Long createTask(TaskRecordCreateRequest request) {
        Long taskId = taskRecordService.createTask(request);
        if (taskId == null) {
            throw new IllegalStateException("Task creation failed");
        }
        return taskId;
    }
}
