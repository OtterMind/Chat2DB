package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configurable XLS/XLSX parser for import preview and execution (MYSQL-IMPORT-003).
 * Lists visible sheets, reads a single selected sheet with configurable start/header rows,
 * and types cell values (string/number/date/boolean/formula-cached/empty). Formulas are
 * never evaluated: only cached results are read and marked as formula values. Damaged
 * workbooks fail before any write.
 */
public final class ExcelParser {

    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    private ExcelParser() {
    }

    /**
     * Lists visible sheets of a workbook.
     */
    public static List<Map<String, Object>> sheets(byte[] bytes, String fileName) {
        try (Workbook workbook = open(bytes, fileName)) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet == null) {
                    continue;
                }
                if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", sheet.getSheetName());
                    entry.put("visible", true);
                    result.add(entry);
                }
            }
            return result;
        } catch (Exception e) {
            throw workbookError(fileName, e);
        }
    }

    /**
     * Parses the selected visible sheet. {@code startRow} skips introductory rows (0-based),
     * {@code headerRow} is the 1-based header row (defaults to startRow + 1 when 0).
     * Cell values carry their type so previews can render dates/numbers/booleans faithfully.
     */
    public static ExcelResult parse(byte[] bytes, String fileName, String sheetName,
                                    int startRow, int headerRow, boolean emptyAsNull, int limit) {
        try (Workbook workbook = open(bytes, fileName)) {
            Sheet sheet = selectSheet(workbook, sheetName);
            int effectiveStart = Math.max(0, startRow);
            int effectiveHeader = headerRow > 0 ? headerRow - 1 : effectiveStart;

            Map<Integer, CellValue> header = headerRow > 0
                    ? readRow(sheet, effectiveHeader, emptyAsNull) : Map.of();
            int maxColumn = header.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<Map<Integer, CellValue>> rows = new ArrayList<>();
            int physicalRow = headerRow > 0 ? effectiveHeader + 1 : effectiveStart;
            int count = 0;
            while (physicalRow <= sheet.getLastRowNum() && count < limit) {
                Map<Integer, CellValue> row = readRow(sheet, physicalRow, emptyAsNull);
                if (!row.isEmpty()) {
                    rows.add(row);
                    maxColumn = Math.max(maxColumn, row.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
                    count++;
                }
                physicalRow++;
            }
            // Normalize every row to the same column count so previews align.
            List<Map<Integer, CellValue>> normalizedRows = new ArrayList<>();
            if (headerRow > 0) {
                normalizedRows.add(normalizeRow(header, maxColumn));
            }
            for (Map<Integer, CellValue> row : rows) {
                normalizedRows.add(normalizeRow(row, maxColumn));
            }
            return new ExcelResult(normalizedRows, headerRow > 0 ? 1 : 0);
        } catch (Exception e) {
            throw workbookError(fileName, e);
        }
    }

    private static Map<Integer, CellValue> normalizeRow(Map<Integer, CellValue> row, int maxColumn) {
        Map<Integer, CellValue> normalized = new LinkedHashMap<>();
        for (int c = 0; c <= maxColumn; c++) {
            CellValue value = row.get(c);
            normalized.put(c, value == null ? new CellValue(null, "empty") : value);
        }
        return normalized;
    }

    private static Workbook open(byte[] bytes, String fileName) {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw workbookError(fileName, e);
        }
    }

    private static BusinessException workbookError(String fileName, Exception e) {
        return new BusinessException("import.excel.unreadable", new Object[]{fileName, e.getMessage()}, e);
    }

    private static Sheet selectSheet(Workbook workbook, String sheetName) {
        if (sheetName != null && !sheetName.isBlank()) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new BusinessException("import.excel.sheetMissing", new Object[]{sheetName});
            }
            int sheetIndex = workbook.getSheetIndex(sheet);
            if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
                throw new BusinessException("import.excel.noVisibleSheet");
            }
            return sheet;
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
                return workbook.getSheetAt(i);
            }
        }
        throw new BusinessException("import.excel.noVisibleSheet");
    }

    private static Map<Integer, CellValue> readRow(Sheet sheet, int rowIndex, boolean emptyAsNull) {
        Row row = sheet.getRow(rowIndex);
        Map<Integer, CellValue> values = new LinkedHashMap<>();
        if (row == null) {
            return values;
        }
        for (Cell cell : row) {
            values.put(cell.getColumnIndex(), readCell(cell, emptyAsNull));
        }
        return values;
    }

    private static CellValue readCell(Cell cell, boolean emptyAsNull) {
        CellType type = cell.getCellType();
        switch (type) {
            case STRING:
                return new CellValue(cell.getStringCellValue(), "string");
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    Date date = cell.getDateCellValue();
                    return new CellValue(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date), "date");
                }
                return new CellValue(new java.math.BigDecimal(cell.getNumericCellValue()).stripTrailingZeros().toPlainString(),
                        "number");
            case BOOLEAN:
                return new CellValue(String.valueOf(cell.getBooleanCellValue()), "boolean");
            case FORMULA:
                // Only the cached result is read; the formula is never evaluated.
                return new CellValue(cachedFormulaValue(cell), "formula");
            case BLANK:
            case _NONE:
            default:
                return new CellValue(emptyAsNull ? null : "", "empty");
        }
    }

    private static String cachedFormulaValue(Cell cell) {
        try {
            switch (cell.getCachedFormulaResultType()) {
                case STRING:
                    return cell.getStringCellValue();
                case NUMERIC:
                    return new java.math.BigDecimal(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public record CellValue(String value, String type) {
    }

    public record ExcelResult(List<Map<Integer, CellValue>> rows, int headerRowCount) {
    }

    public static boolean isExcel(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xls") || lower.endsWith(".xlsx");
    }
}
