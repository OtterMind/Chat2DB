package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.FormatSink;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Base for sinks that produce UTF-8 text files. The underlying stream is owned by the pipeline and
 * is only flushed, never closed, by {@link #close()}.
 */
abstract class TextSink implements FormatSink {

    static final String CRLF = "\r\n";

    private final CountingOutputStream counting;

    private final BufferedWriter writer;

    TextSink(OutputStream output) {
        this.counting = new CountingOutputStream(output);
        this.writer = new BufferedWriter(new OutputStreamWriter(counting, StandardCharsets.UTF_8));
    }

    protected final void write(String text) throws IOException {
        writer.write(text);
    }

    @Override
    public final long bytesWritten() {
        return counting.bytesWritten();
    }

    @Override
    public void close() throws IOException {
        writer.flush();
    }
}
