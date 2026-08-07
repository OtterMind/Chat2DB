package ai.chat2db.community.domain.core.impl.task.export.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.util.WorkbookUtil;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class MultiSheetExcelWriter {

    private static final String DEFAULT_SHEET_NAME = "Data";

    private static final int MAX_SHEET_NAME_LENGTH = 31;

    private final ExcelWriter excelWriter;

    private final List<List<String>> head;

    private final int maxDataRowsPerSheet;

    private final String baseSheetName;

    private WriteSheet currentSheet;

    private int rowsInCurrentSheet;

    private int sheetCount;

    private long totalRows;

    public MultiSheetExcelWriter(ExcelWriter excelWriter, List<List<String>> head,
            SpreadsheetVersion spreadsheetVersion, String baseSheetName) {
        this(excelWriter, head, spreadsheetVersion.getMaxRows(), baseSheetName);
    }

    MultiSheetExcelWriter(ExcelWriter excelWriter, List<List<String>> head, int maxRowsPerSheet,
            String baseSheetName) {
        this.excelWriter = Objects.requireNonNull(excelWriter, "excelWriter");
        this.head = head == null ? Collections.emptyList() : head;
        int headerRows = this.head.stream()
                .filter(Objects::nonNull)
                .mapToInt(List::size)
                .max()
                .orElse(0);
        this.maxDataRowsPerSheet = maxRowsPerSheet - headerRows;
        if (maxDataRowsPerSheet <= 0) {
            throw new IllegalArgumentException("Excel sheet must have room for at least one data row");
        }
        this.baseSheetName = sanitizeBaseSheetName(baseSheetName);
    }

    public void writeRow(List<?> row) {
        Objects.requireNonNull(row, "row");
        initialize();
        if (rowsInCurrentSheet >= maxDataRowsPerSheet) {
            createNextSheet();
        }
        excelWriter.write(Collections.singletonList(row), currentSheet);
        rowsInCurrentSheet++;
        totalRows++;
    }

    public void initialize() {
        if (currentSheet != null) {
            return;
        }
        createNextSheet();
        excelWriter.write(Collections.emptyList(), currentSheet);
    }

    public int getSheetCount() {
        return sheetCount;
    }

    public long getTotalRows() {
        return totalRows;
    }

    private void createNextSheet() {
        int sheetIndex = sheetCount;
        currentSheet = EasyExcel.writerSheet(sheetIndex, sheetName(sheetIndex)).build();
        if (!head.isEmpty()) {
            currentSheet.setHead(head);
        }
        rowsInCurrentSheet = 0;
        sheetCount++;
    }

    private String sheetName(int sheetIndex) {
        String suffix = sheetIndex == 0 ? "" : " (" + (sheetIndex + 1) + ")";
        int baseLength = Math.min(baseSheetName.length(), MAX_SHEET_NAME_LENGTH - suffix.length());
        return baseSheetName.substring(0, baseLength) + suffix;
    }

    private static String sanitizeBaseSheetName(String sheetName) {
        String candidate = sheetName == null || sheetName.isBlank() ? DEFAULT_SHEET_NAME : sheetName.trim();
        String safeName = WorkbookUtil.createSafeSheetName(candidate);
        return safeName == null || safeName.isBlank() ? DEFAULT_SHEET_NAME : safeName;
    }
}
