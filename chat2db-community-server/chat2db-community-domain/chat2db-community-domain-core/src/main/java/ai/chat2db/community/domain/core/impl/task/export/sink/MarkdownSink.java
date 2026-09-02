package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * GitHub-flavoured Markdown table. Pipe characters and line breaks inside values are escaped so
 * every row stays on exactly one table line.
 */
public final class MarkdownSink extends TextSink {

    private boolean started;

    private final boolean append;

    public MarkdownSink(OutputStream output) {
        this(output, false);
    }

    public MarkdownSink(OutputStream output, boolean append) {
        super(output);
        this.append = append;
    }

    @Override
    public void writeSchema(ExportSchema schema, String tableName) throws IOException {
        if (append) {
            started = true;
            return;
        }
        if (started) {
            write("\n");
        }
        started = true;
        StringBuilder header = new StringBuilder();
        StringBuilder separator = new StringBuilder();
        for (String column : schema.getColumnNames()) {
            header.append("| ").append(escape(column)).append(' ');
            separator.append("| --- ");
        }
        write(header.append("|\n").toString());
        write(separator.append("|\n").toString());
    }

    @Override
    public void writeRows(List<List<Object>> batch) throws IOException {
        for (List<Object> row : batch) {
            StringBuilder line = new StringBuilder();
            for (Object value : row) {
                line.append("| ").append(escape(value == null ? "" : String.valueOf(value))).append(' ');
            }
            write(line.append("|\n").toString());
        }
    }

    @Override
    public void finishTable(String tableName) throws IOException {
        // A Markdown table ends with its last row; consecutive tables are separated by a blank line.
    }

    private String escape(String text) {
        return text.replace("|", "\\|").replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
    }
}
