package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskEventLevel;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.extension.TaskExtensionManager;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

@Slf4j
final class TaskRunner<S extends TaskSpec> implements Runnable {

    private final TaskSubmission<S> submission;

    private final RunningTask runningTask;

    private final RunningTaskRegistry runningTaskRegistry;

    private final TaskStorage taskStorage;

    private final TaskExecutor<S> taskExecutor;

    private final ArtifactService artifactService;

    private final TaskExtensionManager taskExtensionManager;

    TaskRunner(TaskSubmission<S> submission, RunningTask runningTask, RunningTaskRegistry runningTaskRegistry,
            TaskStorage taskStorage, TaskExecutor<S> taskExecutor, ArtifactService artifactService,
            TaskExtensionManager taskExtensionManager) {
        this.submission = submission;
        this.runningTask = runningTask;
        this.runningTaskRegistry = runningTaskRegistry;
        this.taskStorage = taskStorage;
        this.taskExecutor = taskExecutor;
        this.artifactService = artifactService;
        this.taskExtensionManager = taskExtensionManager;
    }

    @Override
    public void run() {
        TaskExecutionContextImpl executionContext = new TaskExecutionContextImpl(
                submission.taskId(), runningTask, taskStorage, artifactService);
        try {
            if (!startTask()) {
                return;
            }
            bindExecutionContext();
            taskExtensionManager.runGuarded(submission.extensionContext(), () -> {
                try (Chat2DBContext.StatementGuardScope ignored =
                        Chat2DBContext.bindStatementGuard(taskExtensionManager::beforeStatement)) {
                    taskExecutor.execute(submission.spec(), executionContext);
                }
            });
            ArtifactDraft draft = executionContext.artifactDraft();
            executionContext.finishArtifactWrites();
            logArtifactWritten(executionContext, draft);
            completeSuccessfully(draft);
        } catch (TaskCancelledException | CancellationException e) {
            completeCancelled(executionContext.artifactDraft());
        } catch (TaskExecutionException e) {
            completeFailed(e.getCode(), e.publicMessage(), e.getSafeReason(), e,
                    executionContext.artifactDraft());
        } catch (Throwable e) {
            if (runningTask.cancellationToken().isCancelled() || Thread.currentThread().isInterrupted()) {
                completeCancelled(executionContext.artifactDraft());
            } else {
                completeFailed(TaskErrorCode.TASK_INTERNAL_ERROR.name(), "Task execution failed", null, e,
                        executionContext.artifactDraft());
            }
        } finally {
            try {
                executionContext.closeQuietly();
                runningTask.close();
                runningTaskRegistry.remove(submission.taskId(), runningTask);
                unbindExecutionContext();
            } finally {
                runningTask.markFinished();
            }
        }
    }

    private void logArtifactWritten(TaskExecutionContextImpl executionContext, ArtifactDraft draft) {
        if (draft == null) {
            return;
        }
        executionContext.reportProgress(95, TaskStage.FINALIZING.name(), "Export file written");
        Map<String, Object> details = new LinkedHashMap<>();
        if (draft.getTargetFile() != null) {
            details.put(TaskConstants.FILE_NAME_DETAIL_KEY, draft.getTargetFile().getName());
        }
        if (draft.getMediaType() != null) {
            details.put("mediaType", draft.getMediaType());
        }
        executionContext.logInfo(TaskEventCode.FILE_WRITE_COMPLETED.name(), "Export file written", details);
    }

    private boolean startTask() {
        runningTask.completionLock().lock();
        try {
            if (runningTask.isClosed() || runningTask.cancellationToken().isCancelled()) {
                return false;
            }
            Date now = new Date();
            return taskStorage.compareAndSetStatus(submission.taskId(), TaskStatus.PENDING.name(),
                    TaskStatus.RUNNING.name(),
                    TaskStatusPatch.builder()
                            .progress(TaskConstants.STARTED_PROGRESS)
                            .stage(TaskStage.STARTING.name())
                            .progressMessage("Task started")
                            .startedAt(now)
                            .updatedAt(now)
                            .build(),
                    lifecycleEvent(TaskEventCode.TASK_STARTED.name(), TaskEventLevel.INFO.name(), "Task started"));
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    private void completeSuccessfully(ArtifactDraft draft) {
        runningTask.completionLock().lock();
        String artifactId = null;
        try {
            if (runningTask.cancellationToken().isCancelled()) {
                completeCancelledLocked(draft);
                return;
            }
            if (draft != null) {
                artifactId = artifactService.publish(draft);
                taskStorage.appendEvent(TaskEvent.builder()
                        .taskId(submission.taskId())
                        .level(TaskEventLevel.INFO.name())
                        .code(TaskEventCode.ARTIFACT_PUBLISHED.name())
                        .stage(TaskStage.FINALIZING.name())
                        .message("Artifact published")
                        .details(Map.of(TaskConstants.ARTIFACT_ID_DETAIL_KEY, artifactId))
                        .build());
            }
            Date now = new Date();
            boolean completed = taskStorage.compareAndSetStatus(submission.taskId(), TaskStatus.RUNNING.name(),
                    TaskStatus.SUCCESS.name(),
                    TaskStatusPatch.builder()
                            .progress(TaskConstants.COMPLETED_PROGRESS)
                            .stage(TaskStage.COMPLETED.name())
                            .progressMessage("Task completed successfully")
                            .artifactId(artifactId)
                            .finishedAt(now)
                            .updatedAt(now)
                            .build(),
                    lifecycleEvent(TaskEventCode.TASK_SUCCEEDED.name(), TaskEventLevel.INFO.name(),
                            "Task completed successfully"));
            if (!completed && artifactId != null) {
                artifactService.deletePublished(artifactId);
            }
        } catch (Throwable e) {
            if (artifactId != null) {
                artifactService.deletePublished(artifactId);
            }
            if (runningTask.cancellationToken().isCancelled()) {
                completeCancelledLocked(draft);
            } else {
                completeFailedLocked(TaskErrorCode.ARTIFACT_PUBLISH_FAILED.name(),
                        "Could not publish task artifact", null, e, draft);
            }
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    private void completeFailed(String code, String message, String safeReason, Throwable cause,
            ArtifactDraft draft) {
        runningTask.completionLock().lock();
        try {
            if (runningTask.cancellationToken().isCancelled()) {
                completeCancelledLocked(draft);
                return;
            }
            completeFailedLocked(code, message, safeReason, cause, draft);
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    private void completeFailedLocked(String code, String message, String safeReason, Throwable cause,
            ArtifactDraft draft) {
        artifactService.deleteDraft(draft);
        log.error("Task {} failed", submission.taskId(), cause);
        Date now = new Date();
        taskStorage.compareAndSetStatus(submission.taskId(), TaskStatus.RUNNING.name(), TaskStatus.FAILED.name(),
                TaskStatusPatch.builder()
                        .stage(TaskStage.FAILED.name())
                        .progressMessage(message)
                        .errorCode(code)
                        .errorMessage(message)
                        .finishedAt(now)
                        .updatedAt(now)
                        .build(),
                TaskEvent.builder()
                        .level(TaskEventLevel.ERROR.name())
                        .code(TaskEventCode.TASK_FAILED.name())
                        .stage(TaskStage.FAILED.name())
                        .message(message)
                        .details(failureDetails(code, safeReason))
                        .build());
    }

    private Map<String, Object> failureDetails(String code, String safeReason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (code != null) {
            details.put(TaskConstants.ERROR_CODE_DETAIL_KEY, code);
        }
        if (safeReason != null) {
            details.put(TaskConstants.ERROR_REASON_DETAIL_KEY, safeReason);
        }
        return details;
    }

    private void completeCancelled(ArtifactDraft draft) {
        runningTask.completionLock().lock();
        try {
            completeCancelledLocked(draft);
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    private void completeCancelledLocked(ArtifactDraft draft) {
        artifactService.deleteDraft(draft);
        Date now = new Date();
        taskStorage.compareAndSetStatus(submission.taskId(), TaskStatus.RUNNING.name(), TaskStatus.CANCELLED.name(),
                TaskStatusPatch.builder()
                        .progress(TaskConstants.COMPLETED_PROGRESS)
                        .stage(TaskStage.CANCELLED.name())
                        .progressMessage("Task cancelled")
                        .finishedAt(now)
                        .updatedAt(now)
                        .build(),
                lifecycleEvent(TaskEventCode.TASK_CANCELLED.name(), TaskEventLevel.WARN.name(),
                        "Task cancelled"));
    }

    private TaskEvent lifecycleEvent(String code, String level, String message) {
        return TaskEvent.builder()
                .level(level)
                .code(code)
                .message(message)
                .details(Collections.emptyMap())
                .build();
    }

    private void bindExecutionContext() {
        MDC.put("taskId", String.valueOf(submission.taskId()));
        ContextUtils.setContext(submission.context());
        if (submission.connectInfo() != null) {
            Chat2DBContext.putContext(submission.connectInfo().copy());
        }
    }

    private void unbindExecutionContext() {
        Chat2DBContext.removeContext();
        ContextUtils.removeContext();
        MDC.remove("taskId");
    }
}
