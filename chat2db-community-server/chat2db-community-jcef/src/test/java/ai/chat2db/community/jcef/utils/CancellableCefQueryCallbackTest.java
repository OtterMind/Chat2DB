package ai.chat2db.community.jcef.utils;

import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CancellableCefQueryCallbackTest {

    @Test
    void cancellationSuppressesLateCompletion() {
        RecordingCallback delegate = new RecordingCallback();
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);

        callback.cancel();
        callback.success("late");
        callback.failure(500, "late");

        assertEquals(0, delegate.successCount);
        assertEquals(0, delegate.failureCount);
    }

    @Test
    void onlyTheFirstCompletionReachesTheNativeCallback() {
        RecordingCallback delegate = new RecordingCallback();
        CancellableCefQueryCallback callback = new CancellableCefQueryCallback(delegate);

        callback.success("first");
        callback.success("second");
        callback.failure(500, "late");

        assertEquals(1, delegate.successCount);
        assertEquals("first", delegate.response);
        assertEquals(0, delegate.failureCount);
    }

    private static class RecordingCallback implements CefQueryCallback {
        private int successCount;
        private int failureCount;
        private String response;

        @Override
        public void success(String value) {
            successCount++;
            response = value;
        }

        @Override
        public void failure(int errorCode, String errorMessage) {
            failureCount++;
        }
    }
}
