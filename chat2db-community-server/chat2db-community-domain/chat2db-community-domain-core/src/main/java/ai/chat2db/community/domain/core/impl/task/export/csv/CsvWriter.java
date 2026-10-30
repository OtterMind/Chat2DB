package ai.chat2db.community.domain.core.impl.task.export.csv;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes CSV rows with the byte layout of the EasyExcel CSV path it replaced: a leading UTF-8 byte
 * order mark, comma delimiter, {@code \r\n} record separator, minimal quoting with doubled inner
 * quotes, and empty fields for null values. {@code CsvWriterEasyExcelCompatibilityTest} verifies
 * the bytes against EasyExcel itself, so the layout is evidence, not a claim.
 */
public final class CsvWriter implements Closeable {

    /**
     * Export rows arrive one at a time; a large buffer keeps the file writes proportional to the
     * exported data instead of the row count.
     */
    public static final int BUFFER_SIZE = 128 * 1024;

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    public static final char DEFAULT_DELIMITER = ',';

    private static final char QUOTE = '"';

    private static final char COMMENT_MARKER = '#';

    private static final String RECORD_SEPARATOR = "\r\n";

    private final Writer writer;

    private final char delimiter;

    /** UTF-8 with a comma: the layout this writer replaced, byte for byte. */
    public CsvWriter(OutputStream outputStream) {
        this(outputStream, StandardCharsets.UTF_8, DEFAULT_DELIMITER);
    }

    /**
     * CSV writer with the encoding and delimiter of the export task. Only UTF-8 gets the byte order
     * mark: another character set would read the UTF-8 bytes as mojibake.
     */
    public CsvWriter(OutputStream outputStream, Charset charset, char delimiter) {
        this.delimiter = delimiter;
        try {
            // EasyExcel started every UTF-8 CSV file with a BOM; keeping it preserves the artifact
            // bytes and lets spreadsheet applications detect UTF-8 when the file is opened directly.
            if (StandardCharsets.UTF_8.equals(charset)) {
                outputStream.write(UTF8_BOM);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the CSV byte order mark", e);
        }
        this.writer = new BufferedWriter(new OutputStreamWriter(outputStream, charset), BUFFER_SIZE);
    }

    /**
     * Writes the header row only when the export is configured to contain one, mirroring the
     * {@code containsHeader} switch of the export task spec. The byte order mark is written by the
     * constructor, independently of this switch.
     */
    public void writeHeader(List<String> header, boolean containsHeader) throws IOException {
        if (containsHeader) {
            writeRow(header);
        }
    }

    public void writeRow(List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                writer.write(delimiter);
            }
            writeField(values.get(index), index == 0);
        }
        writer.write(RECORD_SEPARATOR);
    }

    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    private void writeField(String value, boolean firstField) throws IOException {
        if (value == null) {
            // A null value is an empty field and is never quoted.
            return;
        }
        if (value.isEmpty()) {
            // An empty first field must be quoted, otherwise the record looks like a blank line.
            if (firstField) {
                writer.write(QUOTE);
                writer.write(QUOTE);
            }
            return;
        }
        if (!needsQuoting(value)) {
            writer.write(value);
            return;
        }
        writer.write(QUOTE);
        int copied = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == QUOTE) {
                writer.write(value, copied, index - copied + 1);
                writer.write(QUOTE);
                copied = index + 1;
            }
        }
        writer.write(value, copied, value.length() - copied);
        writer.write(QUOTE);
    }

    private boolean needsQuoting(String value) {
        if (value.charAt(0) <= COMMENT_MARKER || value.charAt(value.length() - 1) <= ' ') {
            return true;
        }
        return value.indexOf(delimiter) >= 0 || value.indexOf(QUOTE) >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }
}
