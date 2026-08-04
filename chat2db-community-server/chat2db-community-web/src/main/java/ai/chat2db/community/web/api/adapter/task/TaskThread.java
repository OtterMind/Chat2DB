package ai.chat2db.community.web.api.adapter.task;

import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.tools.model.Context;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CancellationException;

@Slf4j
public class TaskThread extends Thread {

    private Context context;

    private AsyncContext asyncContext;

    private Long taskId;

    private Runnable runnable;

    public TaskThread(Context context, AsyncContext asyncContext, Long taskId, Runnable runnable) {
        super(runnable);
        this.context = context;
        this.asyncContext = asyncContext;
        this.taskId = taskId;
        this.runnable = runnable;
    }

    public boolean cancel() {
        boolean cancelled = asyncContext.stop();
        if (cancelled) {
            interrupt();
        }
        return cancelled;
    }

    @Override
    public void run() {
        try {
            runnable.run();
        } catch (CancellationException e) {
            log.debug("task cancelled: {}", taskId);
        } catch (Exception e) {
            if (!asyncContext.isStopped()) {
                log.error("task error", e);
                asyncContext.error(e.getMessage());
            }
        } finally {
            try {
                asyncContext.finish();
            } finally {
                TaskThreadPoolManager.remove(taskId, this);
            }
        }
    }
}
