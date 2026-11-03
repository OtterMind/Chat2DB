package ai.chat2db.community.jcef.utils;

import org.cef.callback.CefQueryCallback;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Prevents an asynchronous JCEF handler from completing a query after CEF has
 * already cancelled and finalized its native callback.
 */
public final class CancellableCefQueryCallback implements CefQueryCallback {

    private final CefQueryCallback delegate;
    private final Object completionLock = new Object();
    private boolean completed;
    private boolean cancelled;
    private Future<?> worker;

    public CancellableCefQueryCallback(CefQueryCallback delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void cancel() {
        Future<?> workerFuture;
        synchronized (completionLock) {
            completed = true;
            cancelled = true;
            workerFuture = worker;
        }
        if (workerFuture != null) {
            workerFuture.cancel(true);
        }
    }

    public void attachWorker(Future<?> workerFuture) {
        Objects.requireNonNull(workerFuture, "workerFuture");
        boolean cancelWorker;
        synchronized (completionLock) {
            if (worker != null) {
                throw new IllegalStateException("A query callback can only track one worker");
            }
            worker = workerFuture;
            cancelWorker = cancelled;
        }
        if (cancelWorker) {
            workerFuture.cancel(true);
        }
    }

    @Override
    public void success(String response) {
        synchronized (completionLock) {
            if (completed) {
                return;
            }
            completed = true;
            delegate.success(response);
        }
    }

    @Override
    public void failure(int errorCode, String errorMessage) {
        synchronized (completionLock) {
            if (completed) {
                return;
            }
            completed = true;
            delegate.failure(errorCode, errorMessage);
        }
    }
}
