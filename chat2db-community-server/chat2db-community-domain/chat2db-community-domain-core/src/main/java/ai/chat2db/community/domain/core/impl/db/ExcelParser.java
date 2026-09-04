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
import org.apache.poi.openxml4j.util.ZipSecureFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Configurable XLS/XLSX parser for import preview and execution (MYSQL-IMPORT-003).
 * Lists visible sheets, reads a single selected sheet with configurable start/header rows,
 * and types cell values (string/number/date/boolean/formula-cached/empty). Formulas are
 * never evaluated: only cached results are read and marked as formula values. Damaged
 * workbooks fail before any write.
 */
public final class ExcelParser {

    private static final DataFormatter DATA_FORMATTER = new DataFormatter();
    private static final int MAX_SHEETS = 128;
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_COLUMNS = 1_024;
    private static final long MAX_DIMENSION_CELLS = 1_000_000L;
    private static final long MAX_ZIP_ENTRY_SIZE_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_ZIP_TEXT_SIZE_BYTES = 20L * 1024L * 1024L;

    static {
        ZipSecureFile.setMinInflateRatio(0.01D);
        ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_SIZE_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_ZIP_TEXT_SIZE_BYTES);
    }

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
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw workbookError(fileName, e);
        }
    }

    public static List<Map<String, Object>> sheets(File file, String fileName) {
        try (Workbook workbook = open(file, fileName)) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", workbook.getSheetName(i));
                    entry.put("visible", true);
                    result.add(entry);
                }
            }
            return result;
        } catch (BusinessException e) {
            throw e;
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
        return parse(bytes, fileName,
                new ExcelImportConfig(sheetName, Math.max(0, startRow), Math.max(0, headerRow),
                        0, emptyAsNull, ExcelImportConfig.FormulaMode.CACHED_VALUE),
                limit);
    }

    public static ExcelResult parse(byte[] bytes, String fileName, ExcelImportConfig config, int limit) {
        try (Workbook workbook = open(bytes, fileName)) {
            validateWorkbook(workbook);
            return parseWorkbook(workbook, config, limit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw workbookError(fileName, e);
        }
    }

    public static ExcelResult parse(File file, String fileName, String sheetName,
                                    int startRow, int headerRow, boolean emptyAsNull, int limit) {
        return parse(file, fileName,
                new ExcelImportConfig(sheetName, Math.max(0, startRow), Math.max(0, headerRow),
                        0, emptyAsNull, ExcelImportConfig.FormulaMode.CACHED_VALUE),
                limit);
    }

    public static ExcelResult parse(File file, String fileName, ExcelImportConfig config, int limit) {
        try (Workbook workbook = open(file, fileName)) {
            validateWorkbook(workbook);
            return parseWorkbook(workbook, config, limit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw workbookError(fileName, e);
        }
    }

    public static long parseRows(File file, String fileName, ExcelImportConfig config,
                                 BiConsumer<Integer, Map<Integer, CellValue>> rowConsumer) {
        try (Workbook workbook = open(file, fileName)) {
            validateWorkbook(workbook);
            Sheet sheet = selectSheet(workbook, config.sheetName());
            validateSheetDimensions(sheet);
            int rowIndex = config.firstDataRowIndex();
            int endRowIndex = Math.min(config.endRowIndex(), sheet.getLastRowNum());
            long skippedRows = 0L;
            while (rowIndex <= endRowIndex) {
                Map<Integer, CellValue> row = readRow(sheet, rowIndex, config);
                if (isEmptyRow(row)) {
                    skippedRows++;
                } else {
                    rowConsumer.accept(rowIndex, row);
                }
                rowIndex++;
            }
            return skippedRows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw workbookError(fileName, e);
        }
    }

    private static ExcelResult parseWorkbook(Workbook workbook, ExcelImportConfig config, int limit) {
        Sheet sheet = selectSheet(workbook, config.sheetName());
        validateSheetDimensions(sheet);
        Map<Integer, CellValue> header = config.headerRow() > 0
                ? readRow(sheet, config.headerRowIndex(), config) : Map.of();
        int maxColumn = header.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Map<Integer, CellValue>> rows = new ArrayList<>();
        int physicalRow = config.firstDataRowIndex();
        int endRowIndex = Math.min(config.endRowIndex(), sheet.getLastRowNum());
        int count = 0;
        long skippedRows = 0L;
        while (physicalRow <= endRowIndex && count < limit) {
            Map<Integer, CellValue> row = readRow(sheet, physicalRow++, config);
            if (isEmptyRow(row)) {
                skippedRows++;
            } else {
                rows.add(row);
                maxColumn = Math.max(maxColumn, row.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
                count++;
            }
        }
        List<Map<Integer, CellValue>> normalizedRows = new ArrayList<>();
        if (config.headerRow() > 0) {
            normalizedRows.add(normalizeRow(header, maxColumn));
        }
        for (Map<Integer, CellValue> row : rows) {
            normalizedRows.add(normalizeRow(row, maxColumn));
        }
        return new ExcelResult(normalizedRows, config.headerRow() > 0 ? 1 : 0, skippedRows);
    }

    private static void validateWorkbook(Workbook workbook) {
        if (workbook.getNumberOfSheets() > MAX_SHEETS) {
            throw new BusinessException("import.excel.sheetLimitExceeded");
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            validateSheetDimensions(workbook.getSheetAt(i));
        }
    }

    private static void validateSheetDimensions(Sheet sheet) {
        if (sheet == null) {
            return;
        }
        int rowCount = sheet.getLastRowNum() + 1;
        if (rowCount > MAX_ROWS) {
            throw new BusinessException("import.excel.rowLimitExceeded");
        }
        int maxColumnIndex = maxColumnIndex(sheet);
        int columnCount = maxColumnIndex + 1;
        if (columnCount > MAX_COLUMNS) {
            throw new BusinessException("import.excel.columnLimitExceeded");
        }
        if ((long) rowCount * Math.max(1, columnCount) > MAX_DIMENSION_CELLS) {
            throw new BusinessException("import.excel.cellLimitExceeded");
        }
    }

    private static int maxColumnIndex(Sheet sheet) {
        int maxColumnIndex = -1;
        for (Row row : sheet) {
            if (row == null) {
                continue;
            }
            short lastCellNum = row.getLastCellNum();
            if (lastCellNum > 0) {
                maxColumnIndex = Math.max(maxColumnIndex, lastCellNum - 1);
            }
        }
        return maxColumnIndex;
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

    private static Workbook open(File file, String fileName) {
        try (FileInputStream input = new FileInputStream(file)) {
            return WorkbookFactory.create(input);
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
        return readRow(sheet, rowIndex,
                new ExcelImportConfig(null, 0, 1, 0, emptyAsNull, ExcelImportConfig.FormulaMode.CACHED_VALUE));
    }

    private static Map<Integer, CellValue> readRow(Sheet sheet, int rowIndex, ExcelImportConfig config) {
        Row row = sheet.getRow(rowIndex);
        Map<Integer, CellValue> values = new LinkedHashMap<>();
        if (row == null) {
            return values;
        }
        for (Cell cell : row) {
            values.put(cell.getColumnIndex(), readCell(cell, config));
        }
        return values;
    }

    private static boolean isEmptyRow(Map<Integer, CellValue> row) {
        if (row.isEmpty()) {
            return true;
        }
        for (CellValue cellValue : row.values()) {
            if (cellValue != null && cellValue.value() != null && !cellValue.value().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static CellValue readCell(Cell cell, ExcelImportConfig config) {
        CellType type = cell.getCellType();
        switch (type) {
            case STRING:
                return new CellValue(cell.getStringCellValue(), "string");
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    Date date = cell.getDateCellValue();
                    return new CellValue(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date), "date");
                }
                return new CellValue(java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString(),
                        "number");
            case BOOLEAN:
                return new CellValue(String.valueOf(cell.getBooleanCellValue()), "boolean");
            case FORMULA:
                if (config.formulaMode() == ExcelImportConfig.FormulaMode.REJECT) {
                    throw new BusinessException("import.excel.formulaUnsupported");
                }
                // Only the cached result is read; the formula is never evaluated.
                return new CellValue(cachedFormulaValue(cell), "formula");
            case BLANK:
            case _NONE:
            default:
                return new CellValue(config.emptyAsNull() ? null : "", "empty");
        }
    }

    private static String cachedFormulaValue(Cell cell) {
        try {
            switch (cell.getCachedFormulaResultType()) {
                case STRING:
                    return cell.getStringCellValue();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(cell.getDateCellValue());
                    }
                    return java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
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

    public record ExcelResult(List<Map<Integer, CellValue>> rows, int headerRowCount, long skippedRowCount) {
    }

    public static boolean isExcel(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xls") || lower.endsWith(".xlsx");
    }
}
