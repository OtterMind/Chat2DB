package ai.chat2db.community.domain.core.impl.task.imports;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportShardRetryPolicyTest {

    @Test
    void retriesMysqlDeadlocksWithCappedExponentialBackoffAndJitter() throws Exception {
        List<Long> delays = new ArrayList<>();
        ImportShardRetryPolicy policy = new ImportShardRetryPolicy(5, 100L, 450L,
                delays::add, bound -> bound);
        AtomicInteger attempts = new AtomicInteger();

        String result = policy.execute(() -> {
            if (attempts.incrementAndGet() <= 4) {
                throw new SQLException("deadlock", "40001", 1213);
            }
            return "committed";
        });

        assertEquals("committed", result);
        assertEquals(5, attempts.get());
        assertEquals(List.of(125L, 250L, 450L, 450L), delays);
    }

    @Test
    void recognizesPostgresqlDeadlockThroughWrappedCause() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ImportShardRetryPolicy policy = new ImportShardRetryPolicy(1, 0L, 0L,
                ignored -> { }, ignored -> 0L);

        policy.execute(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException(new SQLException("deadlock", "40P01"));
            }
            return null;
        });

        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryNonDeadlockFailures() {
        AtomicInteger attempts = new AtomicInteger();
        ImportShardRetryPolicy policy = new ImportShardRetryPolicy(5, 100L, 500L,
                ignored -> { }, ignored -> 0L);

        assertThrows(SQLException.class, () -> policy.execute(() -> {
            attempts.incrementAndGet();
            throw new SQLException("constraint", "23000", 1062);
        }));

        assertEquals(1, attempts.get());
    }

    @Test
    void stopsAfterFiveRetries() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        ImportShardRetryPolicy policy = new ImportShardRetryPolicy(5, 1L, 100L,
                delays::add, ignored -> 0L);

        assertThrows(SQLException.class, () -> policy.execute(() -> {
            attempts.incrementAndGet();
            throw new SQLException("deadlock", "40P01");
        }));

        assertEquals(6, attempts.get());
        assertEquals(5, delays.size());
        assertTrue(delays.get(4) > delays.get(0));
    }
}
