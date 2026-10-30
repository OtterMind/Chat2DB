package ai.chat2db.community.domain.core.impl.task.imports;

import java.util.HashMap;
import java.util.Map;

/**
 * Contiguous committed prefix of one import run.
 *
 * <p>Parallel workers finish batches in whatever order the database returns, so the number of
 * committed rows is not automatically a position a resume can continue from: a batch that
 * completed early may sit behind a predecessor that is still running. Continuing after the sum of
 * completed batches would either re-read rows that were never stored or, when the sum runs ahead of
 * the unfinished predecessor, skip rows forever.
 *
 * <p>The watermark therefore advances only while the next batch in submission order is known to be
 * committed. Rows of later batches are remembered until the gap closes.
 */
final class ImportCheckpointWatermark {

    /** Rows of batches that completed before their predecessors, keyed by batch sequence. */
    private final Map<Long, Integer> waitingBatches = new HashMap<>();

    private long nextBatch;

    private long committedRows;

    /**
     * Records one committed batch.
     *
     * @param batchSequence submission order of the batch, starting at zero
     * @param rows          source rows the batch consumed, including rows it could not store
     * @return the new contiguous prefix row count, or {@code -1} when the prefix did not move
     */
    long onBatchCommitted(long batchSequence, int rows) {
        if (batchSequence < nextBatch) {
            return -1L; // A repeated report of a batch that is already part of the prefix.
        }
        if (batchSequence > nextBatch) {
            waitingBatches.put(batchSequence, rows);
            return -1L;
        }
        committedRows += rows;
        nextBatch++;
        Integer waitingRows;
        while ((waitingRows = waitingBatches.remove(nextBatch)) != null) {
            committedRows += waitingRows;
            nextBatch++;
        }
        return committedRows;
    }

    /** Source rows whose batch is committed and whose predecessors are all committed too. */
    long committedRows() {
        return committedRows;
    }
}
