package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
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
            List<ArtifactDraft> drafts = executionContext.artifactDrafts();
            executionContext.finishArtifactWrites();
            logArtifactWritten(executionContext, drafts);
            completeSuccessfully(drafts);
        } catch (TaskCancelledException | CancellationException e) {
            completeCancelled(executionContext.artifactDrafts());
        } catch (TaskExecutionException e) {
            completeFailed(e.getCode(), e.publicMessage(), e.getSafeReason(), e,
                    executionContext.artifactDrafts());
        } catch (Throwable e) {
            if (runningTask.cancellationToken().isCancelled() || Thread.currentThread().isInterrupted()) {
                completeCancelled(executionContext.artifactDrafts());
            } else {
                completeFailed(TaskErrorCode.TASK_INTERNAL_ERROR.name(), "Task execution failed", null, e,
                        executionContext.artifactDrafts());
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

    private void logArtifactWritten(TaskExecutionContextImpl executionContext, List<ArtifactDraft> drafts) {
        if (drafts.isEmpty()) {
            return;
        }
        executionContext.reportProgress(95, TaskStage.FINALIZING.name(), "Export file written");
        for (ArtifactDraft draft : drafts) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (draft.getTargetFile() != null) {
                details.put(TaskConstants.FILE_NAME_DETAIL_KEY, draft.getTargetFile().getName());
            }
            if (draft.getMediaType() != null) {
                details.put("mediaType", draft.getMediaType());
            }
            if (draft.getRole() != null) {
                details.put(TaskConstants.ARTIFACT_ROLE_DETAIL_KEY, draft.getRole());
            }
            executionContext.logInfo(TaskEventCode.FILE_WRITE_COMPLETED.name(), "Export file written", details);
        }
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

    private void completeSuccessfully(List<ArtifactDraft> drafts) {
        runningTask.completionLock().lock();
        List<String> published = new ArrayList<>();
        try {
            if (runningTask.cancellationToken().isCancelled()) {
                completeCancelledLocked(drafts);
                return;
            }
            String primaryArtifactId = null;
            for (ArtifactDraft draft : drafts) {
                String artifactId = artifactService.publish(draft);
                published.add(artifactId);
                if (primaryArtifactId == null || TaskArtifactRole.OUTPUT.equals(draft.getRole())) {
                    primaryArtifactId = artifactId;
                }
                taskStorage.saveArtifact(submission.taskId(), TaskArtifact.builder()
                        .artifactId(artifactId)
                        .role(draft.getRole())
                        .mediaType(draft.getMediaType())
                        .sizeBytes(new File(artifactId).length())
                        .createdAt(new Date())
                        .build());
                taskStorage.appendEvent(TaskEvent.builder()
                        .taskId(submission.taskId())
                        .level(TaskEventLevel.INFO.name())
                        .code(TaskEventCode.ARTIFACT_PUBLISHED.name())
                        .stage(TaskStage.FINALIZING.name())
                        .message("Artifact published")
                        .details(Map.of(TaskConstants.ARTIFACT_ID_DETAIL_KEY, artifactId,
                                TaskConstants.ARTIFACT_ROLE_DETAIL_KEY, String.valueOf(draft.getRole())))
                        .build());
            }
            Date now = new Date();
            boolean completed = taskStorage.compareAndSetStatus(submission.taskId(), TaskStatus.RUNNING.name(),
                    TaskStatus.SUCCESS.name(),
                    TaskStatusPatch.builder()
                            .progress(TaskConstants.COMPLETED_PROGRESS)
                            .stage(TaskStage.COMPLETED.name())
                            .progressMessage("Task completed successfully")
                            .artifactId(primaryArtifactId)
                            .artifactIds(published.isEmpty() ? null : List.copyOf(published))
                            .finishedAt(now)
                            .updatedAt(now)
                            .build(),
                    lifecycleEvent(TaskEventCode.TASK_SUCCEEDED.name(), TaskEventLevel.INFO.name(),
                            "Task completed successfully"));
            if (!completed) {
                rollbackPublishedArtifacts(published);
            }
        } catch (Throwable e) {
            rollbackPublishedArtifacts(published);
            if (runningTask.cancellationToken().isCancelled()) {
                completeCancelledLocked(drafts);
            } else {
                completeFailedLocked(TaskErrorCode.ARTIFACT_PUBLISH_FAILED.name(),
                        "Could not publish task artifact", null, e, drafts);
            }
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    /**
     * A lost completion race or a publish failure must not leave orphan files or artifact rows
     * behind, so every already-published output is undone in reverse order.
     */
    private void rollbackPublishedArtifacts(List<String> publishedArtifactIds) {
        for (int index = publishedArtifactIds.size() - 1; index >= 0; index--) {
            artifactService.deletePublished(publishedArtifactIds.get(index));
            taskStorage.deleteArtifact(submission.taskId(), publishedArtifactIds.get(index));
        }
    }

    private void completeFailed(String code, String message, String safeReason, Throwable cause,
            List<ArtifactDraft> drafts) {
        runningTask.completionLock().lock();
        try {
            if (runningTask.cancellationToken().isCancelled()) {
                completeCancelledLocked(drafts);
                return;
            }
            completeFailedLocked(code, message, safeReason, cause, drafts);
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    private void completeFailedLocked(String code, String message, String safeReason, Throwable cause,
            List<ArtifactDraft> drafts) {
        for (ArtifactDraft draft : drafts) {
            artifactService.deleteDraft(draft);
        }
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

    private void completeCancelled(List<ArtifactDraft> drafts) {
        runningTask.completionLock().lock();
        try {
            completeCancelledLocked(drafts);
        } finally {
            runningTask.completionLock().unlock();
        }
    }

    private void completeCancelledLocked(List<ArtifactDraft> drafts) {
        for (ArtifactDraft draft : drafts) {
            artifactService.deleteDraft(draft);
        }
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
