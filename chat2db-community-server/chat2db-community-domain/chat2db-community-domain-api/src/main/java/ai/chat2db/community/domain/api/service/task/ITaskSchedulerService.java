package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.async.AsyncContext;

/**
 * Schedules and controls background task execution.
 */
public interface ITaskSchedulerService {

    /**
     * Creates an async progress callback for the supplied task.
     *
     * @param taskId task identifier.
     * @return async progress callback.
     */
    ITaskAsyncCall asyncCall(Long taskId);

    /**
     * Submits a runnable task for asynchronous execution.
     *
     * @param taskId task identifier.
     * @param asyncContext async execution context.
     * @param runnable runnable to execute.
     */
    void submit(Long taskId, AsyncContext asyncContext, Runnable runnable);

    /**
     * Cancels a running task.
     *
     * @param taskId task identifier.
     * @return true when cancellation was accepted; false when the task was absent or already completed.
     */
    boolean cancel(Long taskId);
}
