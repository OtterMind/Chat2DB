package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.core.impl.task.export.csv.CsvWriter;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Writes the rows an import could not store, so a failed run still tells the user which data was
 * rejected instead of only how many rows failed.
 *
 * <p>Rows are recorded at batch granularity: a JDBC batch reports the failure for the batch, not for
 * a single row, so every row of the failed batch is listed with the same reason. The file is a CSV
 * with the row number, the reason and the row content, and uses the same escaping as exported CSVs.
 */
public final class ImportErrorFileWriter implements Closeable {

    /** File name used for the error artifact of an import task. */
    public static final String FILE_NAME = "error-rows.csv";

    private static final List<String> HEADER = List.of("rowNumber", "reason", "content");

    private final CsvWriter writer;

    public ImportErrorFileWriter(OutputStream outputStream) throws IOException {
        this.writer = new CsvWriter(outputStream);
        this.writer.writeHeader(HEADER, true);
    }

    /** Records one rejected row; the content is a readable join of the row values. */
    public void writeFailure(long rowNumber, String reason, List<Object> values) throws IOException {
        writer.writeRow(List.of(Long.toString(rowNumber), reason == null ? "" : reason, content(values)));
    }

    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    private static String content(List<Object> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                content.append('|');
            }
            Object value = values.get(index);
            content.append(value == null ? "<null>" : value);
        }
        return content.toString();
    }
}