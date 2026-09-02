package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import ai.chat2db.community.domain.api.model.task.pipeline.FormatSink;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * RFC4180 CSV writer, byte-compatible with the previous EasyExcel-based export: UTF-8 BOM, CRLF
 * line endings, null as an unquoted empty field, and minimal quoting (a field is quoted when it is
 * empty, contains a comma, quote, carriage return or line feed, or has leading or trailing
 * whitespace; quotes inside a field are doubled).
 *
 * <p>Replacing the EasyExcel CSV writer also removes the sheet-splitting path, which wrongly
 * chunked CSV rows at the Excel row limit.
 */
public final class CsvSink extends TextSink {

    private static final String BOM = "﻿";

    private final boolean containsHeader;

    private final boolean append;

    private boolean started;

    public CsvSink(OutputStream output, boolean containsHeader) {
        this(output, containsHeader, false);
    }

    public CsvSink(OutputStream output, boolean containsHeader, boolean append) {
        super(output);
        this.containsHeader = containsHeader;
        this.append = append;
        this.started = append;
    }

    @Override
    public void writeSchema(ExportSchema schema, String tableName) throws IOException {
        writePrefix();
        if (containsHeader && !append) {
            writeRow(schema.getColumnNames().stream().map(name -> (Object) name).toList());
        }
    }

    @Override
    public void writeRows(List<List<Object>> batch) throws IOException {
        writePrefix();
        for (List<Object> row : batch) {
            writeRow(row);
        }
    }

    @Override
    public void finishTable(String tableName) throws IOException {
        // CSV is flat: the table name is already reflected in the surrounding archive entry or file.
    }

    private void writePrefix() throws IOException {
        if (started) {
            return;
        }
        started = true;
        write(BOM);
    }

    private void writeRow(List<?> row) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < row.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            appendCell(line, row.get(index), index == 0);
        }
        line.append(CRLF);
        write(line.toString());
    }

    private void appendCell(StringBuilder line, Object value, boolean firstField) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!needsQuoting(text, firstField)) {
            line.append(text);
            return;
        }
        line.append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '"') {
                line.append('"');
            }
            line.append(current);
        }
        line.append('"');
    }

    private boolean needsQuoting(String text, boolean firstField) {
        if (text.isEmpty()) {
            return firstField;
        }
        if (text.chars().anyMatch(current -> current == ',' || current == '"'
                || current == '\r' || current == '\n')) {
            return true;
        }
        return text.length() != text.trim().length();
    }
}
