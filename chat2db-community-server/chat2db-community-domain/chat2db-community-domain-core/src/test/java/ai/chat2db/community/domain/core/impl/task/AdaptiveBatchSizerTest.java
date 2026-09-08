package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tuning behaviour of the batch-size observer: cheap batches grow, expensive batches shrink,
 * and the size always stays inside its bounds.
 */
class AdaptiveBatchSizerTest {

    private static final long FAST_NANOS = 1L * 1_000_000L;

    private static final long SLOW_NANOS = 100L * 1_000_000L;

    @Test
    void clampsInitialValueIntoBounds() {
        assertEquals(100, new AdaptiveBatchSizer(1).batchSize());
        assertEquals(500, new AdaptiveBatchSizer(500).batchSize());
        assertEquals(50_000, new AdaptiveBatchSizer(50_000).batchSize());
        assertEquals(100_000, new AdaptiveBatchSizer(200_000).batchSize());
    }

    @Test
    void doublesWhileBatchesComeBackCheap() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(500);
        for (int round = 0; round < 4; round++) {
            sizer.record(500, FAST_NANOS);
        }
        assertEquals(8_000, sizer.batchSize());
    }

    @Test
    void halvesWhileBatchesComeBackExpensive() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(5_000);
        sizer.record(5_000, SLOW_NANOS);
        assertEquals(2_500, sizer.batchSize());
        sizer.record(2_500, SLOW_NANOS);
        assertEquals(1_250, sizer.batchSize());
    }

    @Test
    void neverLeavesTheBounds() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(5_000);
        for (int round = 0; round < 10; round++) {
            sizer.record(5_000, SLOW_NANOS);
        }
        assertEquals(100, sizer.batchSize());
        for (int round = 0; round < 10; round++) {
            sizer.record(100, FAST_NANOS);
        }
        assertEquals(100_000, sizer.batchSize());
    }

    @Test
    void ignoresInvalidObservations() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(500);
        sizer.record(0, FAST_NANOS);
        sizer.record(500, 0L);
        sizer.record(-1, FAST_NANOS);
        assertEquals(500, sizer.batchSize());
    }
}
