package ai.chat2db.community.domain.core.impl.task.export.excel;

import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import ai.chat2db.community.domain.api.model.task.pipeline.FormatSink;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * XLSX/XLS sink on top of EasyExcel. The workbook writes through a counting stream whose
 * {@code close()} is swallowed, so finishing one workbook never closes the shared ZIP archive.
 */
@Slf4j
final class ExcelSink implements FormatSink {

    private final ExcelWriter excelWriter;

    private final ExcelTypeEnum excelType;

    private final boolean containsHeader;

    private final String sheetName;

    private final CountingExcelStream counting;

    private final SpreadsheetVersion spreadsheetVersion;

    private MultiSheetExcelWriter multiSheetWriter;

    private int truncatedCells;

    ExcelSink(OutputStream output, ExcelTypeEnum excelType, boolean containsHeader, String sheetName) {
        this.excelType = excelType;
        this.containsHeader = containsHeader;
        this.sheetName = sheetName;
        this.spreadsheetVersion = excelType == ExcelTypeEnum.XLS
                ? SpreadsheetVersion.EXCEL97 : SpreadsheetVersion.EXCEL2007;
        this.counting = new CountingExcelStream(output);
        this.excelWriter = EasyExcel.write(counting)
                .charset(StandardCharsets.UTF_8)
                .excelType(excelType)
                .build();
    }

    @Override
    public void writeSchema(ExportSchema schema, String tableName) {
        List<List<String>> head = containsHeader
                ? schema.getColumnNames().stream().map(Collections::singletonList).collect(Collectors.toList())
                : Collections.emptyList();
        multiSheetWriter = new MultiSheetExcelWriter(excelWriter, head, spreadsheetVersion, sheetName);
        multiSheetWriter.initialize();
    }

    @Override
    public void writeRows(List<List<Object>> batch) {
        int maxTextLength = spreadsheetVersion.getMaxTextLength();
        List<List<Object>> sanitized = new ArrayList<>(batch.size());
        for (List<Object> row : batch) {
            List<Object> sanitizedRow = new ArrayList<>(row.size());
            for (Object value : row) {
                if (value instanceof String text && text.length() > maxTextLength) {
                    sanitizedRow.add(text.substring(0, maxTextLength));
                    truncatedCells++;
                } else {
                    sanitizedRow.add(value);
                }
            }
            sanitized.add(sanitizedRow);
        }
        multiSheetWriter.writeRows(sanitized);
    }

    int truncatedCells() {
        return truncatedCells;
    }

    @Override
    public void finishTable(String tableName) {
        // Sheets already carry the table structure; nothing else to close per table.
    }

    @Override
    public long bytesWritten() {
        return counting.bytesWritten();
    }

    @Override
    public void close() {
        excelWriter.finish();
    }

    /**
     * Counts the bytes EasyExcel pushes and keeps the caller-owned destination open.
     */
    private static final class CountingExcelStream extends java.io.FilterOutputStream {

        private long bytesWritten;

        private CountingExcelStream(OutputStream destination) {
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

        @Override
        public void close() throws IOException {
            flush();
        }

        private long bytesWritten() {
            return bytesWritten;
        }
    }
}
