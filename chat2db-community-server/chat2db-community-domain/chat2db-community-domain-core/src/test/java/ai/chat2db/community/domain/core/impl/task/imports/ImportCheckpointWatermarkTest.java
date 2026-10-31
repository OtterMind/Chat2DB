package ai.chat2db.community.domain.core.impl.task.imports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The resumable position may only advance across batches that are committed without a gap: parallel
 * workers finish out of order, and continuing after a sum that skipped a running batch would skip its
 * rows forever.
 */
class ImportCheckpointWatermarkTest {

    @Test
    void batchesThatFinishInOrderAdvanceImmediately() {
        ImportCheckpointWatermark watermark = new ImportCheckpointWatermark();

        assertEquals(1000L, watermark.onBatchCommitted(0L, 1000));
        assertEquals(3000L, watermark.onBatchCommitted(1L, 2000));
        assertEquals(3000L, watermark.committedRows());
    }

    @Test
    void aBatchThatFinishBeforeItsPredecessorWaitsForTheGapToClose() {
        ImportCheckpointWatermark watermark = new ImportCheckpointWatermark();

        assertEquals(-1L, watermark.onBatchCommitted(2L, 300));
        assertEquals(0L, watermark.committedRows(), "nothing before the gap may be reported");
        assertEquals(-1L, watermark.onBatchCommitted(1L, 200));
        assertEquals(0L, watermark.committedRows(), "the first batch is still missing");

        assertEquals(600L, watermark.onBatchCommitted(0L, 100),
                "the prefix now covers batches 0, 1 and 2");
        assertEquals(600L, watermark.committedRows());
    }

    @Test
    void thePrefixKeepsAdvancingWhileLaterBatchesWaitOneByOne() {
        ImportCheckpointWatermark watermark = new ImportCheckpointWatermark();

        assertEquals(-1L, watermark.onBatchCommitted(3L, 40));
        assertEquals(-1L, watermark.onBatchCommitted(2L, 30));
        assertEquals(10L, watermark.onBatchCommitted(0L, 10));
        assertEquals(100L, watermark.onBatchCommitted(1L, 20),
                "the prefix folds batches 2 and 3");
        assertEquals(-1L, watermark.onBatchCommitted(3L, 40),
                "a batch that is already part of the prefix is not reported twice");
        assertEquals(100L, watermark.committedRows());
    }

    @Test
    void aRepeatedReportOfACommittedBatchDoesNotMoveThePrefix() {
        ImportCheckpointWatermark watermark = new ImportCheckpointWatermark();

        assertEquals(10L, watermark.onBatchCommitted(0L, 10));
        assertEquals(-1L, watermark.onBatchCommitted(0L, 10));
        assertEquals(10L, watermark.committedRows());
    }
}
