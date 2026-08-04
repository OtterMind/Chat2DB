package ai.chat2db.community.domain.api.model.async;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for code-review finding core:domain-api-2:
 * the finish flag (and the progress/info/error state read by the callback
 * thread) must be safely published so the polling thread terminates.
 */
class AsyncContextTest {

    @Test
    void sharedStateFieldsAreVolatile() throws Exception {
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("finish").getModifiers()),
                "finish must be volatile so the polling thread observes stop()/finish()");
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("progress").getModifiers()),
                "progress is written by the task thread and read by the callback thread");
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("info").getModifiers()),
                "info is reassigned in callUpdate() and appended by the task thread");
        assertTrue(Modifier.isVolatile(AsyncContext.class.getDeclaredField("error").getModifiers()),
                "error is reassigned in callUpdate() and appended by the task thread");
    }

}
