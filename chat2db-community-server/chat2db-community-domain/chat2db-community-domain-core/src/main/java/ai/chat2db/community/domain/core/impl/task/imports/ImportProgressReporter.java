package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.util.function.LongSupplier;

/**
 * Reports real import progress for one import run.
 *
 * <p>When the total row count is known the percentage is linear in the processed rows. Streaming
 * file imports usually do not know the total, so the percentage then follows the logarithm of the
 * processed rows: it stays monotonic, never claims a completion that was not observed, and the
 * message carries the processed rows and the measured row rate instead.
 *
 * <p>The reporter suppresses a percentage that is not higher than the last one it reported, because
 * the task storage rejects regressions.
 */
public final class ImportProgressReporter {

    /** Progress already reached once the target metadata is loaded; see {@code BaseImporter}. */
    private static final int BASE_PROGRESS = 20;

    private static final int KNOWN_TOTAL_CEILING = 95;

    private static final int KNOWN_TOTAL_BUDGET = KNOWN_TOTAL_CEILING - BASE_PROGRESS;

    private static final int UNKNOWN_TOTAL_CEILING = 90;

    private static final double UNKNOWN_TOTAL_LOG_FACTOR = 4.0d;

    private final TaskExecutionContext context;

    private final Long totalRows;

    /** Source rows already stored before this run; progress is reported at absolute positions. */
    private final long resumeOffset;

    private final LongSupplier nanoTime;

    private final long startedNanos;

    private long lastReportedRows = -1L;

    private int lastProgress = TaskConstants.STARTED_PROGRESS;

    public ImportProgressReporter(TaskExecutionContext context) {
        this(context, null, 0L);
    }

    public ImportProgressReporter(TaskExecutionContext context, Long totalRows) {
        this(context, totalRows, 0L);
    }

    public ImportProgressReporter(TaskExecutionContext context, Long totalRows, long resumeOffset) {
        this(context, totalRows, resumeOffset, System::nanoTime);
    }

    /** Test seam for the rate and ETA, which need a controllable elapsed time. */
    ImportProgressReporter(TaskExecutionContext context, Long totalRows, LongSupplier nanoTime) {
        this(context, totalRows, 0L, nanoTime);
    }

    ImportProgressReporter(TaskExecutionContext context, Long totalRows, long resumeOffset,
            LongSupplier nanoTime) {
        this.context = context;
        this.totalRows = totalRows != null && totalRows > 0L ? totalRows : null;
        this.resumeOffset = Math.max(0L, resumeOffset);
        this.nanoTime = nanoTime;
        this.startedNanos = nanoTime.getAsLong();
    }

    /** Reports the rows processed so far; repeated calls without new rows are ignored. */
    public void report(long processedRows) {
        long rowsInThisRun = Math.max(0L, processedRows);
        long rows = resumeOffset + rowsInThisRun;
        if (rows <= lastReportedRows) {
            return;
        }
        lastReportedRows = rows;
        int progress = Math.max(lastProgress, progress(rows));
        lastProgress = progress;
        double elapsedSeconds = (nanoTime.getAsLong() - startedNanos) / 1_000_000_000.0d;
        // The rate measures this run, while the position and ETA use the absolute source row.
        Double rowsPerSecond = elapsedSeconds >= 1.0d ? rowsInThisRun / elapsedSeconds : null;
        Long etaSeconds = null;
        if (rowsPerSecond != null && rowsPerSecond > 0 && totalRows != null) {
            etaSeconds = Math.max(0L, (long) Math.ceil((totalRows - rows) / rowsPerSecond));
        }
        context.reportProgress(TaskProgress.builder()
                .progress(progress)
                .stage(TaskStage.IMPORTING.name())
                .message(message(rows, rowsPerSecond))
                .processedRows(rows)
                .totalRows(totalRows)
                .rowsPerSecond(rowsPerSecond)
                .etaSeconds(etaSeconds)
                .build());
    }

    /** The highest percentage reported so far; never decreases. */
    public int lastProgress() {
        return lastProgress;
    }

    private int progress(long rows) {
        if (totalRows != null) {
            long completed = Math.min(KNOWN_TOTAL_BUDGET, rows * KNOWN_TOTAL_BUDGET / totalRows);
            return BASE_PROGRESS + (int) completed;
        }
        int growth = (int) Math.min(UNKNOWN_TOTAL_CEILING - BASE_PROGRESS,
                UNKNOWN_TOTAL_LOG_FACTOR * Math.log(rows + 1.0d));
        return BASE_PROGRESS + Math.max(0, growth);
    }

    /**
     * Keeps the processed row count in the second whitespace-separated token, which the task UI and
     * the progress assertions read.
     */
    private String message(long rows, Double rowsPerSecond) {
        StringBuilder message = new StringBuilder("Imported ").append(rows);
        if (totalRows != null) {
            message.append(" of ").append(totalRows).append(" rows");
        } else {
            message.append(" rows (total unknown)");
        }
        if (rowsPerSecond != null) {
            message.append(", ").append(Math.round(rowsPerSecond)).append(" rows/s");
        }
        return message.toString();
    }
}