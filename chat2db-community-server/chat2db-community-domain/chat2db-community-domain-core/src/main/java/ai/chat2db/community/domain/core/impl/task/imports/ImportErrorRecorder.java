package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportFailurePolicy;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Collects the rows an import could not store into the task's error artifact.
 *
 * <p>The artifact is staged lazily: an import without rejected rows never creates one, and a task
 * that fails before reading a row leaves no empty error file behind. Publishing is the task layer's
 * job (it happens on the terminal status), so this class only stages and fills the draft.
 *
 * <p>A broken error file never fails the import: the rejected rows are diagnostics, and losing them
 * must not replace the task result. The failure is reported once as a warning event.
 */
public final class ImportErrorRecorder implements Closeable {

    /** Directory of the error files; served through the task error-artifact download endpoint. */
    private static final String ERROR_DIRECTORY_NAME = "import-errors";

    private static final String MEDIA_TYPE = "text/csv";

    private final TaskExecutionContext context;

    private final ImportFailurePolicy policy;

    private ImportErrorFileWriter writer;

    private long rejectedRows;

    private boolean reportFailure;

    public ImportErrorRecorder(TaskExecutionContext context, ImportTaskSpec spec) {
        if (context == null) {
            throw new IllegalArgumentException("A task context is required to record rejected rows");
        }
        this.context = context;
        this.policy = ImportFailurePolicy.from(spec.getFailurePolicy());
    }

    public ImportFailurePolicy policy() {
        return policy;
    }

    /** True when a rejected batch is retried row by row instead of failing the task. */
    public boolean toleratesRowFailures() {
        return policy.toleratesRowFailures();
    }

    /**
     * Records every row of a batch the database rejected, all with the batch-level reason: a
     * parameterized batch reports one failure for the whole batch, not one per row.
     */
    public synchronized void recordRejectedBatch(List<Long> rowNumbers, List<List<Object>> rows, String reason) {
        if (rows == null) {
            return;
        }
        for (int index = 0; index < rows.size(); index++) {
            record(rowNumber(rowNumbers, index), rows.get(index), reason);
        }
    }

    /** Records one row that failed on its own during a row-by-row retry. */
    public synchronized void recordRejectedRow(long rowNumber, List<Object> row, String reason) {
        record(rowNumber, row, reason);
    }

    /**
     * Applies the failure policy to a row the importer could not convert. The row always lands in
     * the error artifact with the conversion reason; {@code ABORT} then fails the task, while
     * {@code SKIP_ERROR_ROWS} lets the importer continue with the next row.
     */
    public synchronized void rejectRow(long rowNumber, List<Object> values, RuntimeException failure) {
        record(rowNumber, values, reason(failure));
        if (!toleratesRowFailures()) {
            throw failure;
        }
    }

    /** Public, localized reason of a conversion failure. */
    private static String reason(RuntimeException failure) {
        return ImportTaskErrors.from(failure, "import.file.failed").publicMessage();
    }

    /** Rejected rows written to the error artifact so far. */
    public synchronized long rejectedRows() {
        return rejectedRows;
    }

    @Override
    public synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            reportCloseFailure(e);
        } finally {
            writer = null;
        }
        try {
            context.logInfo(TaskEventCode.IMPORT_ROWS_SKIPPED.name(),
                    "Rejected rows recorded",
                    Map.of("rejectedRows", rejectedRows, "errorFile", ImportErrorFileWriter.FILE_NAME));
        } catch (RuntimeException | Error ignored) {
            // The import result has already been decided.
        }
    }

    private void record(long rowNumber, List<Object> row, String reason) {
        try {
            writer().writeFailure(rowNumber, reason, row);
            rejectedRows++;
        } catch (IOException | RuntimeException | Error failure) {
            reportOnce(failure);
        }
    }

    private ImportErrorFileWriter writer() throws IOException {
        if (writer == null) {
            ArtifactDraft draft = context.createErrorArtifact(errorDirectory(), ImportErrorFileWriter.FILE_NAME,
                    MEDIA_TYPE);
            if (draft == null || draft.getTemporaryFile() == null) {
                throw new IllegalStateException("The task context did not stage an error artifact");
            }
            writer = new ImportErrorFileWriter(Files.newOutputStream(draft.getTemporaryFile().toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        }
        return writer;
    }

    private static String errorDirectory() {
        return new File(ConfigUtils.getBasePath(), ERROR_DIRECTORY_NAME).getAbsolutePath();
    }

    private static long rowNumber(List<Long> rowNumbers, int index) {
        if (rowNumbers == null || index >= rowNumbers.size() || rowNumbers.get(index) == null) {
            return -1L;
        }
        return rowNumbers.get(index);
    }

    private void reportOnce(Throwable failure) {
        if (reportFailure) {
            return;
        }
        reportFailure = true;
        try {
            context.logWarn(TaskEventCode.IMPORT_BATCH_FAILED.name(), "Could not write the import error file",
                    Map.of("message", StringUtils.defaultString(failure.getMessage())));
        } catch (RuntimeException | Error ignored) {
            // Reporting the failure must not replace the import result either.
        }
    }

    private void reportCloseFailure(IOException failure) {
        try {
            context.logWarn(TaskEventCode.IMPORT_BATCH_FAILED.name(), "Could not write the import error file",
                    Map.of("message", StringUtils.defaultString(failure.getMessage())));
        } catch (RuntimeException | Error ignored) {
            // The import result has already been decided.
        }
    }
}
