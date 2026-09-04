package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;

public record ExcelImportConfig(String sheetName, int startRow, int headerRow, int endRow,
                                boolean emptyAsNull, FormulaMode formulaMode) {

    public enum FormulaMode {
        CACHED_VALUE,
        REJECT
    }

    public static ExcelImportConfig from(Map<String, Object> options) {
        Map<String, Object> safeOptions = options == null ? Map.of() : options;
        boolean hasHeader = !Boolean.FALSE.equals(safeOptions.get("hasHeader"));
        int headerRow = hasHeader ? numberOption(safeOptions, "headerRow", 1) : 0;
        ExcelImportConfig config = new ExcelImportConfig(
                stringOption(safeOptions, "sheetName"),
                numberOption(safeOptions, "startRow", 0),
                headerRow,
                numberOption(safeOptions, "endRow", 0),
                !Boolean.FALSE.equals(safeOptions.get("emptyAsNull")),
                formulaMode(safeOptions.get("formulaMode")));
        config.validate();
        return config;
    }

    public int headerRowIndex() {
        return headerRow > 0 ? headerRow - 1 : -1;
    }

    public int firstDataRowIndex() {
        return headerRow > 0 ? Math.max(headerRow, startRow) : startRow;
    }

    public int endRowIndex() {
        return endRow > 0 ? endRow - 1 : Integer.MAX_VALUE;
    }

    private void validate() {
        if (startRow < 0 || headerRow < 0 || endRow < 0) {
            throw new BusinessException("import.excel.invalidRange");
        }
        if (endRow > 0 && endRowIndex() < firstDataRowIndex()) {
            throw new BusinessException("import.excel.invalidRange");
        }
    }

    private static int numberOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && StringUtils.isNotBlank(string)) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException("import.excel.invalidRange", null, e);
            }
        }
        return defaultValue;
    }

    private static String stringOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value == null || StringUtils.isBlank(String.valueOf(value)) ? null : String.valueOf(value).trim();
    }

    private static FormulaMode formulaMode(Object value) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return FormulaMode.CACHED_VALUE;
        }
        try {
            return FormulaMode.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("import.excel.unsupportedFormulaMode", null, e);
        }
    }
}
