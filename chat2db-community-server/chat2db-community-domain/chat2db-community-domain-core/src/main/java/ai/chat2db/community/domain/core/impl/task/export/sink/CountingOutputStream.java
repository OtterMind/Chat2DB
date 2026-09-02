package ai.chat2db.community.domain.core.impl.task.export.sink;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Counts bytes pushed through a sink and shields the caller-owned destination from sink-side
 * {@code close()} calls, so several tables can share one file or ZIP archive.
 */
final class CountingOutputStream extends FilterOutputStream {

    private long bytesWritten;

    CountingOutputStream(OutputStream destination) {
        super(destination);
    }

    @Override
    public void write(int value) throws IOException {
        out.write(value);
        bytesWritten++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        out.write(bytes, offset, length);
        bytesWritten += length;
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    /**
     * Flushes only: the destination stream belongs to the export pipeline, not to this sink.
     */
    @Override
    public void close() throws IOException {
        flush();
    }

    long bytesWritten() {
        return bytesWritten;
    }
}
