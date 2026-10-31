package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * Checkpoint writes for one import run.
 *
 * <p>The stored position is the number of <em>contiguously committed</em> source rows: rows of
 * batches whose execution returned successfully and whose predecessors all returned successfully
 * too. Parallel workers finish batches out of order, so the caller advances a watermark instead of
 * summing completed batches; summing would skip rows of a predecessor that is still running. Rows
 * that were only read or buffered never move it, because a resumed task stores everything after
 * that position again.
 *
 * <p>A checkpoint is written whenever the contiguous position moves, with no batch or time budget in
 * between. That is deliberately tighter than a throttled write: {@code LocalTaskManager.cancelTask}
 * persists the CANCELLED status <em>before</em> it asks the running import to stop, and the batches
 * still in flight keep committing afterwards. The storage accepts the final position of a terminal
 * task, so the flush after the workers drained is durable. A per-commit write keeps the durable
 * position within the batches that are still running, and costs one small snapshot write per commit
 * - the same order as the progress update every batch already performs. A repeated position is not
 * rewritten.
 *
 * <p>A failing checkpoint never fails the import: the position is an optimization, and the import
 * result is worth far more than the resume point. The failure is reported as a warning event so it
 * remains visible in the task log.
 */
public final class ImportCheckpointWriter {

    private final TaskExecutionContext context;

    /** Source rows already stored before this run; added to every reported position. */
    private final long resumeOffset;

    private final String targetTable;

    private final String conflictPolicy;

    private long processedRows;

    private long writtenRows;

    public ImportCheckpointWriter(TaskExecutionContext context, ImportTaskSpec spec) {
        if (context == null) {
            throw new IllegalArgumentException("A task context is required to checkpoint an import");
        }
        this.context = context;
        this.resumeOffset = ImportResumability.resumeOffset(spec);
        this.targetTable = spec.getTarget() == null ? null : spec.getTarget().getTableName();
        this.conflictPolicy = ImportResumability.duplicatePolicy(spec).name();
    }

    /**
     * Records the contiguous committed prefix of this run; the checkpoint stores its absolute
     * position in the source file.
     */
    public synchronized void batchCommitted(long rowsProcessedInThisRun) {
        this.processedRows = resumeOffset + Math.max(0L, rowsProcessedInThisRun);
        write();
    }

    /** Writes the current position again; used on terminal paths that must not lose it. */
    public synchronized void flush() {
        write();
    }

    /** Absolute source rows whose batch is committed; 0 before the first committed batch. */
    public synchronized long processedRows() {
        return processedRows;
    }

    private void write() {
        if (processedRows <= writtenRows) {
            return;
        }
        writtenRows = processedRows;
        String checkpointJson = new ImportCheckpoint(processedRows, targetTable, conflictPolicy).toJson();
        try {
            context.saveCheckpoint(checkpointJson);
        } catch (TaskCancelledException cancelled) {
            // The task is already ending; a missing checkpoint must not replace its terminal result.
        } catch (RuntimeException | Error failure) {
            reportFailure(failure);
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            context.logWarn(TaskEventCode.IMPORT_CHECKPOINT_FAILED.name(),
                    "Could not save the import checkpoint",
                    Map.of("processedRows", processedRows,
                            "message", StringUtils.defaultString(failure.getMessage())));
        } catch (RuntimeException | Error ignored) {
            // Reporting a checkpoint failure must not replace the import result either.
        }
    }
}
