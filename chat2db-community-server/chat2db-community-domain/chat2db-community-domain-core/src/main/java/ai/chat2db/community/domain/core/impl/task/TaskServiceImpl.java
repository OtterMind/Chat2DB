package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportDuplicatePolicy;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.task.TaskDeletionService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportCheckpoint;
import ai.chat2db.community.domain.core.impl.task.imports.ImportResumability;
import com.alibaba.fastjson2.JSON;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
public class TaskServiceImpl implements TaskService {

    private final TaskStorage taskStorage;

    private final LocalTaskManager localTaskManager;

    private final TaskDeletionService deletionService;

    private final IDbWorkspaceDataSourceService workspaceDataSourceService;

    private final ConnectionContextConverter connectionContextConverter;

    public TaskServiceImpl(TaskStorage taskStorage, LocalTaskManager localTaskManager,
            TaskDeletionService deletionService, IDbWorkspaceDataSourceService workspaceDataSourceService,
            ConnectionContextConverter connectionContextConverter) {
        this.taskStorage = taskStorage;
        this.localTaskManager = localTaskManager;
        this.deletionService = deletionService;
        this.workspaceDataSourceService = workspaceDataSourceService;
        this.connectionContextConverter = connectionContextConverter;
    }

    @PostConstruct
    void recoverInterruptedArtifactDeletions() {
        deletionService.retryPendingDeletions();
    }

    @Override
    public Long submitExport(ExportTaskSpec spec) {
        return submit(spec);
    }

    @Override
    public Long submitImport(ImportTaskSpec spec) {
        return submit(spec);
    }

    /**
     * Continues an owned import as a new task. The source task keeps its status, its checkpoint and
     * its error file; only its stored spec and checkpoint are read, so a failed or cancelled task
     * stays exactly as the user saw it.
     */
    @Override
    public Long resumeImport(Long taskId) {
        Task source = taskStorage.get(taskId).orElse(null);
        if (!isOwnedBy(source, currentOwner())) {
            throw new DataNotFoundException();
        }
        ImportTaskSpec spec = resumedSpec(source.getSpecJson(), source.getCheckpointJson());
        spec.setResumeRootTaskId(spec.getResumeRootTaskId() == null ? source.getId() : spec.getResumeRootTaskId());
        spec.setResumeParentTaskId(source.getId());
        spec.setResumeAttempt(spec.getResumeAttempt() == null ? 1 : spec.getResumeAttempt() + 1);
        spec.setResumeRootTaskName(StringUtils.defaultIfBlank(spec.getResumeRootTaskName(), source.getName()));
        spec.setTaskName(StringUtils.defaultIfBlank(spec.getResumeRootTaskName(), spec.getTaskType()));
        // A resume request carries only the task id, so unlike a normal import request nothing has
        // bound the connection of the original target. Resolving it here keeps the resumed run on
        // the same database instead of submitting a task that executes without a context.
        return submit(spec, resolveConnection(spec.getTarget()));
    }

    /**
     * Resolves the saved data source of a task target into the connection a normal import request
     * gets bound from its data source id.
     *
     * @throws BusinessException with {@code task.resume.dataSourceMissing} when the target has no
     *         usable data source; the task is rejected instead of submitted without a connection
     */
    private ConnectInfo resolveConnection(TaskTargetSnapshot target) {
        Long dataSourceId = target == null ? null : target.getDataSourceId();
        if (dataSourceId == null) {
            throw new BusinessException("task.resume.dataSourceMissing");
        }
        DbConnectionContextRequest request = new DbConnectionContextRequest();
        request.setDataSourceId(dataSourceId);
        request.setDatabaseName(target.getDatabaseName());
        request.setSchemaName(target.getSchemaName());
        WorkspaceDataSource dataSource = workspaceDataSourceService.queryDisplayDataSourceById(dataSourceId, true);
        if (dataSource == null) {
            throw new BusinessException("task.resume.dataSourceMissing");
        }
        return connectionContextConverter.datasource2connectInfo(request, dataSource, dataSource.getUrl());
    }

    /**
     * Rebuilds the import parameters of a finished task. A task without a stored spec or checkpoint
     * is rejected instead of silently starting from the beginning, because that would store rows the
     * original run already wrote.
     *
     * @throws BusinessException with the {@code task.resume.*} code of the missing or unusable input
     */
    static ImportTaskSpec resumedSpec(String specJson, String checkpointJson) {
        if (StringUtils.isBlank(specJson)) {
            throw new BusinessException("task.resume.missingSpec");
        }
        if (StringUtils.isBlank(checkpointJson)) {
            throw new BusinessException("task.resume.missingCheckpoint");
        }
        ImportTaskSpec spec;
        try {
            spec = JSON.parseObject(specJson, ImportTaskSpec.class);
        } catch (RuntimeException e) {
            throw new BusinessException("task.resume.invalidSpec", null, e);
        }
        if (spec == null || !ImportResumability.isResumable(spec)) {
            throw new BusinessException("task.resume.unsupportedType");
        }
        File sourceFile = new File(StringUtils.defaultString(spec.getSourceFile()));
        if (!sourceFile.isFile() || !sourceFile.canRead()) {
            throw new BusinessException("task.resume.sourceMissing");
        }
        ImportCheckpoint checkpoint;
        try {
            checkpoint = ImportCheckpoint.fromJson(checkpointJson);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("task.resume.invalidCheckpoint", null, e);
        }
        // Rows are skipped by position, so the resumed run continues after the rows the checkpoint
        // already stored. The row numbers come from the source file and are unaffected by the skip.
        spec.setResumeFromRow(checkpoint.processedRows());
        // Parallel workers may have committed batches beyond the contiguous checkpoint before the
        // original run stopped. The resumed run re-reads that overlap, so it must skip rows that
        // already exist instead of failing on the first one. A serial run's checkpoint is exact, so
        // its duplicate policy stays untouched. The checkpoint's conflictPolicy records what the
        // original run used, not what a continuation needs.
        if (TaskExecutionMode.isFast(spec.getMode())) {
            spec.setDuplicatePolicy(ImportDuplicatePolicy.IGNORE.name());
        }
        return spec;
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
        Task task = get(taskId);
        if (task == null) {
            throw new DataNotFoundException();
        }
        if (!TaskStatus.isTerminal(task.getStatus())) {
            throw new BusinessException(TaskConstants.DELETE_ACTIVE_FORBIDDEN_MESSAGE_CODE);
        }
        deletionService.delete(task);
    }

    @Override
    public boolean cancel(Long taskId) {
        Task task = taskStorage.get(taskId).orElse(null);
        if (!isOwnedBy(task, currentOwner())) {
            throw new DataNotFoundException();
        }
        return localTaskManager.cancelTask(taskId);
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
        File file = deletionService.resolveArtifact(task);
        if (!file.isFile() || !file.canRead()) {
            throw new DataNotFoundException();
        }
        return TaskDownload.builder().fileName(new File(task.getArtifactId()).getName())
                .fileUri(file.toURI().toString()).build();
    }

    /**
     * Resolves the rejected-rows file of a task. Unlike the main artifact this is also available for
     * failed and cancelled tasks, because the whole point of the file is to explain a failure.
     */
    @Override
    public TaskDownload resolveErrorArtifact(Long taskId) {
        Task task = get(taskId);
        if (task == null || StringUtils.isBlank(task.getErrorArtifactId())) {
            throw new DataNotFoundException();
        }
        File file = new File(task.getErrorArtifactId());
        if (!file.isFile() || !file.canRead()) {
            throw new DataNotFoundException();
        }
        return TaskDownload.builder().fileName(file.getName())
                .fileUri(file.toURI().toString()).build();
    }

    /** Submits with the connection bound by the current request, the way import and export work. */
    private <S extends TaskSpec> Long submit(S spec) {
        return submit(spec, Chat2DBContext.getConnectInfo());
    }

    /**
     * Submits with an explicit connection. A caller that does not run inside a bound connection
     * scope (resume) passes the connection it resolved itself.
     */
    private <S extends TaskSpec> Long submit(S spec, ConnectInfo connectInfo) {
        localTaskManager.validate(spec);
        if (spec.getTarget() == null) {
            throw new IllegalArgumentException("Task target is required");
        }
        Context context = ContextUtils.queryContext();
        TaskOwner owner = owner(context);
        Task task = Task.builder()
                .type(spec.getTaskType())
                .name(StringUtils.defaultIfBlank(spec.getTaskName(), spec.getTaskType()))
                .target(spec.getTarget())
                // The business parameters of the task, kept so the task can be re-created with the
                // same configuration (history reuse and resume). Credentials live in ConnectInfo and
                // are deliberately not part of a spec.
                .specJson(specJson(spec))
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

    static String specJson(TaskSpec spec) {
        try {
            return JSON.toJSONString(spec);
        } catch (RuntimeException e) {
            // A task without a reusable spec still runs; only resume and history reuse are lost.
            return null;
        }
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
