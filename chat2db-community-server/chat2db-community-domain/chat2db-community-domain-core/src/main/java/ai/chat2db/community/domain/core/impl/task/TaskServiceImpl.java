package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportPreview;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.imports.ImportColumnResolver;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFileProbe;
import ai.chat2db.community.domain.core.impl.task.imports.excel.ImportPreviewListener;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.apache.commons.csv.CSVFormat;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import com.google.common.util.concurrent.Striped;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    private final Striped<Lock> deletionLocks = Striped.lazyWeakLock(64);

    private final TaskStorage taskStorage;

    private final LocalTaskManager localTaskManager;

    private final ArtifactService artifactService;

    public TaskServiceImpl(TaskStorage taskStorage, LocalTaskManager localTaskManager, ArtifactService artifactService) {
        this.taskStorage = taskStorage;
        this.localTaskManager = localTaskManager;
        this.artifactService = artifactService;
    }

    @Override
    public Long submitExport(ExportTaskSpec spec) {
        return submit(spec);
    }

    @Override
    public Long submitImport(ImportTaskSpec spec) {
        return submit(spec);
    }

    @Override
    public ImportPreview previewImport(ImportTaskSpec spec) {
        java.io.File source = new java.io.File(StringUtils.defaultString(spec.getSourceFile()));
        if (!source.isFile() || !source.canRead()) {
            throw new BusinessException("task.import.preview.sourceUnreadable", null);
        }
        String format = StringUtils.upperCase(StringUtils.trimToEmpty(spec.getFormat()),
                java.util.Locale.ROOT);
        List<TableColumn> tableColumns = loadTableColumns(spec);
        return switch (format) {
            case "CSV" -> previewCsv(source, spec, tableColumns);
            case "XLSX", "XLS" -> previewExcel(source, spec, tableColumns, format);
            default -> throw new BusinessException("task.import.preview.unsupportedFormat", null);
        };
    }

    private List<TableColumn> loadTableColumns(ImportTaskSpec spec) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return Chat2DBContext.getDbMetaData().columns(Chat2DBContext.getConnection(),
                new TableMetadataRequest(connectInfo.getDatabaseName(), connectInfo.getSchemaName(),
                        spec.getTarget() == null ? null : spec.getTarget().getTableName()));
    }

    private ImportPreview previewCsv(java.io.File source, ImportTaskSpec spec, List<TableColumn> tableColumns) {
        try {
            java.nio.charset.Charset charset = ImportFileProbe.effectiveCharset(source,
                    spec.getOptions() == null ? null : spec.getOptions().getCharset());
            char quote = ImportFileProbe.quoteChar(
                    spec.getOptions() == null ? null : spec.getOptions().getQuoteChar());
            char delimiter = ImportFileProbe.delimiterChar(
                    spec.getOptions() == null ? null : spec.getOptions().getDelimiter(), charset, source);
            CSVFormat format = ImportFileProbe.csvFormat(delimiter, quote);
            List<List<String>> rows = ImportFileProbe.readSample(source, charset, format,
                    ImportFileProbe.sampleRows());
            return buildPreview(rows, tableColumns, spec, charset.name(), String.valueOf(delimiter));
        } catch (java.io.IOException e) {
            throw new BusinessException("task.import.preview.failed", null, e);
        }
    }

    private ImportPreview previewExcel(java.io.File source, ImportTaskSpec spec, List<TableColumn> tableColumns,
            String format) {
        ImportPreviewListener listener = new ImportPreviewListener();
        EasyExcel.read(source, listener)
                .excelType("XLS".equals(format) ? ExcelTypeEnum.XLS : ExcelTypeEnum.XLSX)
                .sheet()
                .headRowNumber(1)
                .doRead();
        return buildPreview(listener.rows(), tableColumns, spec, null, null);
    }

    private ImportPreview buildPreview(List<List<String>> rows, List<TableColumn> tableColumns,
            ImportTaskSpec spec, String detectedCharset, String detectedDelimiter) {
        List<String> headers = rows.isEmpty() ? List.of() : rows.get(0);
        ImportColumnResolver.Resolution resolution =
                ImportColumnResolver.resolve(tableColumns, headers, spec.getOptions());
        return ImportPreview.builder()
                .fileColumns(headers)
                .columnMatches(resolution.matches())
                .missingTableColumns(resolution.missingTableColumns())
                .sampleRows(rows.size() <= 1 ? List.of()
                        : rows.subList(1, rows.size()).stream().map(row -> (List<String>) row).toList())
                .detectedCharset(detectedCharset)
                .detectedDelimiter(detectedDelimiter)
                .build();
    }

    @Override
    public Long resume(Long taskId) {
        Task task = get(taskId);
        if (task == null || !TaskStatus.PENDING.name().equals(task.getStatus())
                || StringUtils.isBlank(task.getSpecJson())
                || taskStorage.listResumeStates(taskId).isEmpty()) {
            // Only a task that startup reconciliation left pending with checkpoints is resumable;
            // anything else is reported like a missing task.
            throw new DataNotFoundException();
        }
        TaskSpec spec = parseSpec(task);
        localTaskManager.validate(spec);
        Context context = ContextUtils.queryContext();
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        localTaskManager.resume(task, spec, context, connectInfo);
        return taskId;
    }

    private TaskSpec parseSpec(Task task) {
        String type = task.getType();
        if (TaskType.DATA_FILE_IMPORT.name().equals(type) || TaskType.SQL_FILE_IMPORT.name().equals(type)) {
            return JSON.parseObject(task.getSpecJson(), ImportTaskSpec.class);
        }
        return JSON.parseObject(task.getSpecJson(), ExportTaskSpec.class);
    }

    @Override
    public PageResponse<Task> list(TaskQuery query) {
        TaskOwner owner = currentOwner();
        TaskQuery effectiveQuery = query == null ? new TaskQuery() : query;
        effectiveQuery.setUserId(owner.userId());
        effectiveQuery.setOrganizationId(owner.organizationId());
        return taskStorage.list(effectiveQuery);
    }

    @Override
    public Task get(Long taskId) {
        Task task = taskStorage.get(taskId).orElse(null);
        return isOwnedBy(task, currentOwner()) ? task : null;
    }

    @Override
    public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
        if (get(taskId) == null) {
            return List.of();
        }
        return taskStorage.listEvents(taskId, Math.max(0L, afterSequence), limit);
    }

    @Override
    public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
        if (get(taskId) == null) {
            return List.of();
        }
        return taskStorage.listEventsBefore(taskId, beforeSequence, limit);
    }

    @Override
    public void delete(Long taskId) {
        Lock deletionLock = deletionLocks.get(taskId);
        deletionLock.lock();
        try {
            Task task = get(taskId);
            if (task == null) {
                throw new DataNotFoundException();
            }
            if (!TaskStatus.isTerminal(task.getStatus())) {
                throw new BusinessException(TaskConstants.DELETE_ACTIVE_FORBIDDEN_MESSAGE_CODE);
            }
            List<ArtifactService.PublishedArtifactDeletion> deletions = artifactPaths(task).stream()
                    .map(artifactService::stagePublishedDeletion)
                    .toList();
            try {
                if (!taskStorage.deleteTerminalTask(taskId,
                        () -> deletions.forEach(artifactService::commitPublishedDeletion))) {
                    throw new DataNotFoundException();
                }
            } catch (RuntimeException e) {
                for (ArtifactService.PublishedArtifactDeletion deletion : deletions) {
                    try {
                        artifactService.restorePublishedDeletion(deletion);
                    } catch (RuntimeException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                }
                throw e;
            }
        } finally {
            deletionLock.unlock();
        }
    }

    /**
     * Every file the task may still own: the artifact rows plus the legacy single-artifact column of
     * tasks written before artifact rows existed.
     */
    private List<String> artifactPaths(Task task) {
        List<String> paths = taskStorage.listArtifacts(task.getId()).stream()
                .map(TaskArtifact::getArtifactId)
                .collect(Collectors.toCollection(ArrayList::new));
        if (StringUtils.isNotBlank(task.getArtifactId()) && !paths.contains(task.getArtifactId())) {
            paths.add(task.getArtifactId());
        }
        return paths;
    }

    @Override
    public int activeTaskCount() {
        TaskOwner owner = currentOwner();
        return localTaskManager.activeTaskCount(owner.userId(), owner.organizationId());
    }

    @Override
    public void prepareForUserExit() {
        TaskOwner owner = currentOwner();
        localTaskManager.prepareForUserExit(owner.userId(), owner.organizationId());
    }

    @Override
    public void abortUserExit() {
        localTaskManager.abortUserExit();
    }

    @Override
    public TaskDownload resolveArtifact(Long taskId) {
        Task task = get(taskId);
        if (task == null || !TaskStatus.SUCCESS.name().equals(task.getStatus())
                || StringUtils.isBlank(task.getArtifactId())) {
            throw new DataNotFoundException();
        }
        return downloadFor(task.getArtifactId());
    }

    @Override
    public TaskDownload resolveArtifact(Long taskId, String artifactId) {
        Task task = get(taskId);
        if (task == null || !TaskStatus.SUCCESS.name().equals(task.getStatus())) {
            throw new DataNotFoundException();
        }
        // The parameter is only a lookup key; the served path always comes from the stored row, so
        // a caller cannot name an arbitrary file.
        TaskArtifact artifact = taskStorage.listArtifacts(taskId).stream()
                .filter(candidate -> candidate.getArtifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(DataNotFoundException::new);
        return downloadFor(artifact.getArtifactId());
    }

    @Override
    public List<TaskArtifact> listArtifacts(Long taskId) {
        return get(taskId) == null ? List.of() : taskStorage.listArtifacts(taskId);
    }

    private TaskDownload downloadFor(String artifactPath) {
        File file = new File(artifactPath);
        if (!file.isFile() || !file.canRead()) {
            throw new DataNotFoundException();
        }
        return TaskDownload.builder().fileName(file.getName()).fileUri(file.toURI().toString()).build();
    }

    private <S extends TaskSpec> Long submit(S spec) {
        localTaskManager.validate(spec);
        if (spec.getTarget() == null) {
            throw new IllegalArgumentException("Task target is required");
        }
        Context context = ContextUtils.queryContext();
        TaskOwner owner = owner(context);
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        Task task = Task.builder()
                .type(spec.getTaskType())
                .name(StringUtils.defaultIfBlank(spec.getTaskName(), spec.getTaskType()))
                .target(spec.getTarget())
                .userId(owner.userId())
                .organizationId(owner.organizationId())
                .build();
        TaskEvent createdEvent = TaskEvent.builder()
                .level(TaskEventLevel.INFO.name())
                .code(TaskEventCode.TASK_CREATED.name())
                .stage(TaskStage.PENDING.name())
                .message("Task created")
                .details(Collections.emptyMap())
                .build();
        task = localTaskManager.submit(task, createdEvent, spec, context, connectInfo);
        return task.getId();
    }

    private TaskOwner currentOwner() {
        return owner(ContextUtils.queryContext());
    }

    private TaskOwner owner(Context context) {
        Long userId = context != null && context.getLoginUser() != null
                ? context.getLoginUser().getId() : null;
        Long organizationId = context == null ? null : context.getOrganizationId();
        return new TaskOwner(userId, organizationId);
    }

    private boolean isOwnedBy(Task task, TaskOwner owner) {
        return task != null && Objects.equals(task.getUserId(), owner.userId())
                && Objects.equals(task.getOrganizationId(), owner.organizationId());
    }

    private record TaskOwner(Long userId, Long organizationId) {
    }
}
