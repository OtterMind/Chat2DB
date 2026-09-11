package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.Task;

import java.io.File;

/** Coordinates durable deletion of terminal tasks and their output files. */
public interface TaskDeletionService {
    /** Registers and attempts deletion of an owned terminal task. */
    void delete(Task task);

    /** Retries unfinished deletions within the automatic attempt limit. */
    void retryPendingDeletions();

    /** Resolves the task's output, including a file waiting for deletion. */
    File resolveArtifact(Task task);
}
