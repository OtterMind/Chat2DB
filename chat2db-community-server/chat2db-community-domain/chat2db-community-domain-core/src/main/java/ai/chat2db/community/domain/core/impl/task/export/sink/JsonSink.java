package ai.chat2db.community.domain.core.impl.task.export.sink;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Emits one JSON array of row objects, the shape the console result grid consumes. Rows are
 * serialized one at a time instead of buffering a batch and cutting the surrounding brackets off
 * the serialized array.
 */
public final class JsonSink extends JsonObjectSink {

    private boolean started;

    public JsonSink(OutputStream output) {
        super(output);
    }

    @Override
    public void writeRows(List<List<Object>> batch) throws IOException {
        for (List<Object> row : batch) {
            write(started ? ",\n" : "[\n");
            started = true;
            write(objectOf(row));
        }
    }

    @Override
    public void finishTable(String tableName) throws IOException {
        if (started) {
            write("\n]");
        } else {
            write("[]");
        }
        started = false;
    }
}
