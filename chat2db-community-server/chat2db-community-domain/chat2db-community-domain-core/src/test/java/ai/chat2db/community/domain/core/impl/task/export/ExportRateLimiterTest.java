package ai.chat2db.community.domain.core.impl.task.export;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportRateLimiterTest {

    private static final class Fake {

        private final List<Long> sleeps = new ArrayList<>();

        private long now = 1_000_000_000L;

        private final ExportRateLimiter limiter = new ExportRateLimiter(() -> now, sleeps::add);
    }

    @Test
    void disabledLimiterNeverSleeps() {
        Fake fake = new Fake();
        fake.limiter.configureLimits(0, 0);

        fake.limiter.acquire(100_000, 100_000_000L);

        assertEquals(List.of(), fake.sleeps);
    }

    @Test
    void rowLimitSleepsForTheRowCostAndByteLimitTakesTheLargerDelay() {
        Fake fake = new Fake();
        fake.limiter.configureLimits(1000, 0);

        fake.limiter.acquire(100, 0);

        assertEquals(1, fake.sleeps.size());
        assertEquals(100L, fake.sleeps.get(0));

        Fake bytes = new Fake();
        bytes.limiter.configureLimits(1000, 1000);
        bytes.limiter.acquire(10, 5000);

        assertEquals(1, bytes.sleeps.size());
        assertEquals(5000L, bytes.sleeps.get(0), "byte dimension costs 5s, row dimension 10ms");
    }

    @Test
    void sustainedUsageAccumulatesDebtAcrossCalls() {
        Fake fake = new Fake();
        fake.limiter.configureLimits(1000, 0);

        fake.limiter.acquire(50, 0);
        fake.limiter.acquire(50, 0);

        assertTrue(fake.sleeps.size() >= 2);
        assertEquals(50L, fake.sleeps.get(0));
        assertEquals(100L, fake.sleeps.get(1), "with a frozen clock the debt of the first window is added");
    }
}
