package ai.chat2db.community.domain.api.model.task.pipeline;

import java.io.Closeable;
import java.util.List;

/**
 * Write end of the export pipeline: producers push schema and row batches, the sink turns them into
 * one concrete file format. Instances own exactly one output stream and must not buffer unboundedly.
 *
 * <p>{@link #close()} finishes the format (trailing structure and flush) but deliberately does not
 * close the underlying stream: the caller owns it, because several tables may share one archive.
 */
public interface FormatSink extends Closeable {

    /**
     * Called once per table before any of its rows are written.
     */
    void writeSchema(ExportSchema schema, String tableName) throws java.io.IOException;

    /**
     * Appends rows in order; the caller keeps ownership of the batches and may reuse them.
     */
    void writeRows(List<List<Object>> batch) throws java.io.IOException;

    /**
     * Signals that no more rows of {@code tableName} will arrive; sinks that need per-table
     * structure close it here.
     */
    void finishTable(String tableName) throws java.io.IOException;

    /**
     * Bytes produced so far, used for size limits and progress.
     */
    long bytesWritten();
}
