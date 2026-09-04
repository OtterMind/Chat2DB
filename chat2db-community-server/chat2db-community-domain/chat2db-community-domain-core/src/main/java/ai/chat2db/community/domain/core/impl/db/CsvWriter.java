package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.task.CsvOptions;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

/** CSV writer paired with {@link CsvParser}. */
public final class CsvWriter implements Closeable {

    private final CsvOptions options;
    private final Writer writer;
    private final char delimiter;
    private final char quote;
    private final char escape;
    private final String newline;
    private final boolean emptyAsNull;

    public CsvWriter(CsvOptions options, OutputStream outputStream) {
        this.options = (options == null ? CsvOptions.defaults() : options).validate();
        String encoding = CsvOptions.AUTO_ENCODING.equals(this.options.getEncoding())
                ? CsvOptions.DEFAULT_ENCODING : this.options.getEncoding();
        this.writer = new OutputStreamWriter(outputStream, Charset.forName(encoding));
        this.delimiter = this.options.getDelimiter().charAt(0);
        this.quote = this.options.getQuote().charAt(0);
        this.escape = this.options.getEscape().charAt(0);
        this.newline = this.options.rowSeparator();
        this.emptyAsNull = Boolean.TRUE.equals(this.options.getEmptyAsNull());
    }

    public void writeRow(List<?> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                writer.write(delimiter);
            }
            writeField(values.get(index));
        }
        writer.write(newline);
    }

    private void writeField(Object rawValue) throws IOException {
        if (rawValue == null) {
            return;
        }
        String value = neutralizeSpreadsheetFormula(Objects.toString(rawValue, ""));
        boolean quoteField = value.indexOf(delimiter) >= 0
                || value.indexOf(quote) >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.isEmpty() && emptyAsNull;
        if (!quoteField) {
            writer.write(value);
            return;
        }
        writer.write(quote);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == quote || (escape != quote && current == escape)) {
                writer.write(escape);
            }
            writer.write(current);
        }
        writer.write(quote);
    }

    private static String neutralizeSpreadsheetFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
