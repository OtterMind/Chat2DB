package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;

import java.util.Date;
import java.util.List;

/**
 * Task lifecycle rules shared by every {@code TaskStorage} implementation. The rules live here
 * rather than inside a storage class because the file and database implementations must not be able
 * to drift apart on status transitions, progress monotonicity or event paging.
 */
public final class TaskLifecyclePolicy {

    /**
     * Status written by older releases for a cancellation that had not finished yet; only kept so
     * persisted rows remain transitionable.
     */
    private static final String LEGACY_CANCELLING_STATUS = "CANCELLING";

    private TaskLifecyclePolicy() {
    }

    public static boolean isLegalTransition(String source, String target) {
        if (TaskStatus.PENDING.name().equals(source)) {
            return TaskStatus.RUNNING.name().equals(target) || TaskStatus.FAILED.name().equals(target);
        }
        if (TaskStatus.RUNNING.name().equals(source)) {
            return TaskStatus.SUCCESS.name().equals(target) || TaskStatus.FAILED.name().equals(target)
                    || TaskStatus.PENDING.name().equals(target);
        }
        if (LEGACY_CANCELLING_STATUS.equals(source)) {
            return TaskStatus.FAILED.name().equals(target);
        }
        return false;
    }

    /**
     * Applies a status transition to {@code task}, which the caller has already verified is legal.
     */
    public static void applyStatusPatch(Task task, String targetStatus, TaskStatusPatch patch) {
        TaskStatusPatch effectivePatch = patch == null ? new TaskStatusPatch() : patch;
        int previousProgress = task.getProgress() == null ? TaskConstants.PENDING_PROGRESS : task.getProgress();
        task.setStatus(targetStatus);
        if (TaskStatus.SUCCESS.name().equals(targetStatus)) {
            task.setProgress(TaskConstants.COMPLETED_PROGRESS);
        } else if (!TaskStatus.isTerminal(targetStatus) && effectivePatch.getProgress() != null) {
            task.setProgress(Math.max(previousProgress, Math.min(TaskConstants.MAX_RUNNING_PROGRESS,
                    effectivePatch.getProgress())));
        } else {
            task.setProgress(previousProgress);
        }
        if (effectivePatch.getStage() != null) {
            task.setStage(effectivePatch.getStage());
        }
        task.setProgressMessage(effectivePatch.getProgressMessage());
        task.setErrorCode(TaskStatus.FAILED.name().equals(targetStatus) ? effectivePatch.getErrorCode() : null);
        task.setErrorMessage(TaskStatus.FAILED.name().equals(targetStatus) ? effectivePatch.getErrorMessage() : null);
        task.setArtifactId(TaskStatus.SUCCESS.name().equals(targetStatus)
                ? primaryArtifactId(effectivePatch) : null);
        if (effectivePatch.getStartedAt() != null) {
            task.setStartedAt(effectivePatch.getStartedAt());
        }
        if (effectivePatch.getFinishedAt() != null) {
            task.setFinishedAt(effectivePatch.getFinishedAt());
        }
        task.setUpdatedAt(effectivePatch.getUpdatedAt() == null ? new Date() : effectivePatch.getUpdatedAt());
    }

    /**
     * The legacy single-artifact column always names the primary output, so a multi-artifact
     * completion records the first id of the list.
     */
    private static String primaryArtifactId(TaskStatusPatch patch) {
        List<String> artifactIds = patch.getArtifactIds();
        if (artifactIds != null && !artifactIds.isEmpty()) {
            return artifactIds.get(0);
        }
        return patch.getArtifactId();
    }

    /**
     * Progress a running task may report: never below the started value, never into the completed
     * range, which only a SUCCESS transition may write.
     */
    public static int runningProgress(int requested) {
        return Math.max(TaskConstants.STARTED_PROGRESS, Math.min(TaskConstants.MAX_RUNNING_PROGRESS, requested));
    }

    public static int eventLimit(int limit) {
        return Math.max(1, Math.min(TaskConstants.MAX_EVENT_LIMIT, limit));
    }
}
