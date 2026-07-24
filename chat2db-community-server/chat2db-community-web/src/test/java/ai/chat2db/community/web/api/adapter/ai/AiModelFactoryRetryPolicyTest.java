package ai.chat2db.community.web.api.adapter.ai;

import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiModelFactoryRetryPolicyTest {

    @Test
    void streamingRequestsAttemptExactlyOnce() {
        assertEquals(1, countAttempts(AiModelFactory.createRetryTemplate(
                AiModelFactory.RequestMode.STREAMING)));
    }

    @Test
    void synchronousRequestsPreserveTheDefaultRetryPolicy() {
        int defaultAttempts = countAttempts(RetryTemplate.defaultInstance());

        assertEquals(3, defaultAttempts);
        assertEquals(defaultAttempts, countAttempts(AiModelFactory.createRetryTemplate(
                AiModelFactory.RequestMode.SYNCHRONOUS)));
    }

    private int countAttempts(RetryTemplate retryTemplate) {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("retry policy test");
        }));
        return attempts.get();
    }
}
