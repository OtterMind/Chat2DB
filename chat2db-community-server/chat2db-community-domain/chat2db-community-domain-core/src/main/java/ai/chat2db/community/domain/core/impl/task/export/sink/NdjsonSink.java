package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.FormatSink;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * JSON Lines sink: one self-contained object per row, so consumers can stream-parse it and an
 * interrupted file still holds every complete row written before the cut.
 */
public final class NdjsonSink extends JsonObjectSink {

    public NdjsonSink(OutputStream output) {
        super(output);
    }

    @Override
    public void writeRows(List<List<Object>> batch) throws IOException {
        for (List<Object> row : batch) {
            write(objectOf(row));
            write("\n");
        }
    }

    @Override
    public void finishTable(String tableName) throws IOException {
        // Each line is a complete document; there is no file-level structure to close.
    }
}
