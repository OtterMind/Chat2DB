package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.tools.exception.BusinessException;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Locale;

@Data
@EqualsAndHashCode(callSuper = true)
public class ExcelOptions extends ImportValueOptions {
    private Integer sheetIndex = 0;
    private Boolean hasHeader = true;
    private Integer headerRow = 1;
    private Integer dataStartRow;
    private Integer dataEndRow;
    private String columnRange = "";
    private Boolean emptyAsNull = true;

    public Integer getDataStartRow() {
        return dataStartRow == null ? (Boolean.TRUE.equals(hasHeader) ? headerRow + 1 : 1) : dataStartRow;
    }

    public ExcelOptions validate() {
        validateFormats();
        if (sheetIndex == null || sheetIndex < 0) throw new BusinessException("import.preview.excelSheetMissing");
        if (hasHeader == null || headerRow == null || headerRow < 1
                || emptyAsNull == null || getDataStartRow() < 1
                || hasHeader && headerRow >= getDataStartRow()
                || dataEndRow != null && dataEndRow < getDataStartRow()) {
            throw new BusinessException("import.preview.invalidRowRange");
        }
        columnRange = columnRange == null ? "" : columnRange.trim().toUpperCase(Locale.ROOT);
        if (!columnRange.isEmpty() && (!columnRange.matches("[A-Z]{1,3}:[A-Z]{1,3}")
                || firstColumn() > lastColumn() || lastColumn() > 16383)) {
            throw new BusinessException("import.preview.invalidColumnRange");
        }
        return this;
    }

    public int firstColumn() {
        return columnRange.isEmpty() ? 0 : columnIndex(columnRange.split(":")[0]);
    }

    public int lastColumn() {
        return columnRange.isEmpty() ? 16383 : columnIndex(columnRange.split(":")[1]);
    }

    private static int columnIndex(String name) {
        int index = 0;
        for (char character : name.toCharArray()) index = index * 26 + character - 'A' + 1;
        return index - 1;
    }
}
