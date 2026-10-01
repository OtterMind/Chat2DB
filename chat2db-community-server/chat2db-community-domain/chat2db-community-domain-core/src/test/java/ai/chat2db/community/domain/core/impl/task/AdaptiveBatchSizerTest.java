package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tuning behaviour of the batch-size observer: the size starts inside its bounds, grows while the
 * measured throughput keeps improving, shrinks when throughput regresses, ignores blips inside the
 * hysteresis band, stays fixed in standard mode, and never leaves its bounds.
 */
class AdaptiveBatchSizerTest {

    private static final long MILLI = 1_000_000L;

    @Test
    void clampsInitialValueIntoBounds() {
        assertEquals(100, new AdaptiveBatchSizer(1).batchSize());
        assertEquals(500, new AdaptiveBatchSizer(500).batchSize());
        assertEquals(50_000, new AdaptiveBatchSizer(50_000).batchSize());
        assertEquals(100_000, new AdaptiveBatchSizer(200_000).batchSize());
    }

    @Test
    void growsWhileThroughputImproves() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(1_000);
        long nanos = 10 * MILLI;
        sizer.record(1_000, nanos); // first observation only establishes the reference
        assertEquals(1_000, sizer.batchSize());
        for (int round = 0; round < 4; round++) {
            nanos = Math.max(1L, nanos / 2); // half the time for the same rows: twice the throughput
            sizer.record(1_000, nanos);
        }
        assertEquals(16_000, sizer.batchSize(), "improving throughput doubles the batch each window");
    }

    @Test
    void shrinksWhenThroughputRegressesAndStopsAtTheFloor() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(8_000);
        long nanos = 5 * MILLI;
        sizer.record(8_000, nanos); // reference
        for (int round = 0; round < 64; round++) {
            nanos = nanos * 4; // four times slower: throughput clearly regresses
            sizer.record(sizer.batchSize(), nanos);
        }
        assertEquals(100, sizer.batchSize(), "a slow target drives the batch down to the floor");
    }

    @Test
    void repeatedGrowthStaysBoundedWithoutOverflow() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(1_000);
        long nanos = 10 * MILLI;
        sizer.record(1_000, nanos);
        for (int round = 0; round < 64; round++) {
            nanos = Math.max(1L, nanos / 2);
            sizer.record(1_000, nanos);
        }
        assertEquals(100_000, sizer.batchSize());
    }

    @Test
    void keepsSizeWhenThroughputIsFlat() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(5_000);
        sizer.record(5_000, 5 * MILLI);
        sizer.record(5_000, 5 * MILLI);
        sizer.record(5_000, 5 * MILLI);
        assertEquals(5_000, sizer.batchSize(), "no improvement and no regression keeps the size");
    }

    @Test
    void aSmallMeasurementBlipKeepsTheSize() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(5_000);
        sizer.record(5_000, 10 * MILLI); // 500_000 rows/s becomes the reference
        sizer.record(5_000, 10_500_000L); // ~4.8% slower, inside the 10% band
        assertEquals(5_000, sizer.batchSize(), "a blip inside the band must not retune the batch");
        double blipThroughput = 5_000.0D * 1_000_000_000.0D / 10_500_000L;
        double smoothed = sizer.referenceRowsPerSecond();
        assertEquals(500_000.0D, 5_000.0D * 1_000_000_000.0D / (10 * MILLI), 1.0D,
                "the first report is the unsmoothed reference");
        assertTrue(smoothed > blipThroughput && smoothed < 500_000.0D,
                "the reference moves towards the blip but keeps part of the previous value: " + smoothed);
    }

    @Test
    void standardModeStaysFixedAtItsInitialSize() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(500, false);
        sizer.record(5_000, 10 * MILLI);
        sizer.record(5_000, 100 * MILLI);
        sizer.record(5_000, 1 * MILLI);
        assertEquals(500, sizer.batchSize(), "standard mode must not tune the batch size");
        assertEquals(-1.0D, sizer.referenceRowsPerSecond(), "a fixed sizer keeps no reference");
    }

    @Test
    void ignoresInvalidObservations() {
        AdaptiveBatchSizer sizer = new AdaptiveBatchSizer(500);
        sizer.record(0, MILLI);
        sizer.record(500, 0L);
        sizer.record(-1, MILLI);
        assertEquals(500, sizer.batchSize());
    }
}