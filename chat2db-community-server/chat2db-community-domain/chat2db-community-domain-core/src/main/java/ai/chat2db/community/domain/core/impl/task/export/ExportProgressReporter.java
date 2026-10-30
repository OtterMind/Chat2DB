package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.util.function.LongSupplier;

/**
 * Reports exported rows and throughput while the task percentage stays on its stage boundaries.
 *
 * <p>Exports do not know how many rows the source will produce, so they do not report a total or
 * an ETA. The progress value is the last stage percentage; the storage accepts an equal percentage
 * and updates the row counters without moving the task backwards. Reports are throttled because
 * every row calls into the logger.
 */
public final class ExportProgressReporter {

    private static final long REPORT_INTERVAL_ROWS = 1_000L;

    private static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private final TaskExecutionContext context;

    private final LongSupplier nanoTime;

    private final long startedNanos;

    private long processedRows;

    private long lastReportedRows = -1L;

    private long lastReportNanos;

    private boolean reported;

    private int lastProgress;

    private String stage = TaskStage.EXPORTING.name();

    public ExportProgressReporter(TaskExecutionContext context, int initialProgress) {
        this(context, initialProgress, System::nanoTime);
    }

    /** Test seam for the rate, which needs a controllable elapsed time. */
    ExportProgressReporter(TaskExecutionContext context, int initialProgress, LongSupplier nanoTime) {
        this.context = context;
        this.lastProgress = Math.max(0, initialProgress);
        this.nanoTime = nanoTime;
        this.startedNanos = nanoTime.getAsLong();
    }

    /** Moves the stage percentage; counters reported later keep this value. */
    public synchronized void stage(int progress, String stage, String message) {
        lastProgress = Math.max(lastProgress, progress);
        if (stage != null && !stage.isBlank()) {
            this.stage = stage;
        }
        context.reportProgress(lastProgress, stage, message);
    }

    /** Adds rows processed since the previous call, reporting at most once per second or 1,000 rows. */
    public synchronized void addRows(long rows) {
        if (rows <= 0L) {
            return;
        }
        processedRows += rows;
        long now = nanoTime.getAsLong();
        if (reported
                && now - lastReportNanos < REPORT_INTERVAL_NANOS
                && processedRows - lastReportedRows < REPORT_INTERVAL_ROWS) {
            return;
        }
        report(now);
    }

    /** Reports the final row count even when the throttle would have skipped it. */
    public synchronized void flush() {
        report(nanoTime.getAsLong());
    }

    public synchronized long processedRows() {
        return processedRows;
    }

    private void report(long now) {
        if (processedRows == lastReportedRows && reported) {
            return;
        }
        double elapsedSeconds = (now - startedNanos) / 1_000_000_000.0d;
        Double rowsPerSecond = elapsedSeconds >= 1.0d ? processedRows / elapsedSeconds : null;
        lastReportedRows = processedRows;
        lastReportNanos = now;
        reported = true;
        context.reportProgress(TaskProgress.builder()
                .progress(lastProgress)
                .stage(stage)
                .message("Exported " + processedRows + " rows")
                .processedRows(processedRows)
                .rowsPerSecond(rowsPerSecond)
                .build());
    }
}
