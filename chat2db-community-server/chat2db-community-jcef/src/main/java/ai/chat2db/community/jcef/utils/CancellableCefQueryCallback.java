package ai.chat2db.community.jcef.utils;

import org.cef.callback.CefQueryCallback;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prevents an asynchronous JCEF handler from completing a query after CEF has
 * already cancelled and finalized its native callback.
 */
public final class CancellableCefQueryCallback implements CefQueryCallback {

    private final CefQueryCallback delegate;
    private final AtomicBoolean completed = new AtomicBoolean();

    public CancellableCefQueryCallback(CefQueryCallback delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void cancel() {
        completed.set(true);
    }

    @Override
    public void success(String response) {
        if (completed.compareAndSet(false, true)) {
            delegate.success(response);
        }
    }

    @Override
    public void failure(int errorCode, String errorMessage) {
        if (completed.compareAndSet(false, true)) {
            delegate.failure(errorCode, errorMessage);
        }
    }
}
