package ai.chat2db.community.domain.core.impl.task.imports.reader;

import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.tools.exception.BusinessException;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelReader;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.read.metadata.ReadSheet;
import org.apache.poi.ss.usermodel.DateUtil;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared Excel row selection and cell conversion for preview and execution. */
public final class ExcelImportReader {
    public static List<String> sheets(File file) {
        try (ExcelReader reader = EasyExcel.read(file).build()) {
            return reader.excelExecutor().sheetList().stream().map(ReadSheet::getSheetName).toList();
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidExcelFile", null, e);
        }
    }

    public static void read(File file, ExcelOptions settings, int limit, int minimumColumns,
            Consumer<Map<Integer, String>> headerConsumer,
            BiConsumer<Map<Integer, ImportCell>, Integer> rowConsumer, Runnable checkCancelled) {
        ExcelOptions options = settings.validate();
        List<String> sheets = sheets(file);
        if (options.getSheetIndex() >= sheets.size()) throw new BusinessException("import.preview.excelSheetMissing");
        EasyExcel.read(file, new AnalysisEventListener<Map<Integer, ReadCellData<?>>>() {
            private int emitted;
            private boolean hasHeader;
            private int sourceColumnCount = options.getColumnRange().isEmpty()
                    ? 0 : options.lastColumn() - options.firstColumn() + 1;

            @Override
            public void invoke(Map<Integer, ReadCellData<?>> data, AnalysisContext context) {
                checkCancelled.run();
                int rowNumber = context.readRowHolder().getRowIndex() + 1;
                boolean isHeader = options.getHasHeader() && rowNumber == options.getHeaderRow();
                if (!isHeader && rowNumber < options.getDataStartRow()) return;
                if (options.getDataEndRow() != null && rowNumber > options.getDataEndRow() && !isHeader) return;
                int dataLastColumn = data.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                int lastColumn = options.getColumnRange().isEmpty()
                        ? Math.max(Math.max(minimumColumns - 1, dataLastColumn), sourceColumnCount - 1)
                        : options.lastColumn();
                Map<Integer, ImportCell> cells = new LinkedHashMap<>();
                for (int column = options.firstColumn(); column <= Math.min(lastColumn, options.lastColumn()); column++) {
                    cells.put(column - options.firstColumn(), cell(data.get(column), context, options, column));
                }
                if (!options.getHasHeader() && !hasHeader && cells.isEmpty()) return;
                if (isHeader || !options.getHasHeader() && !hasHeader) {
                    sourceColumnCount = Math.max(sourceColumnCount, cells.size());
                    Map<Integer, String> header = new LinkedHashMap<>();
                    cells.forEach((column, cell) -> header.put(column, isHeader ? org.apache.commons.lang3.StringUtils.defaultIfBlank(cell.display(), "column_" + (column + 1)) : "column_" + (column + 1)));
                    headerConsumer.accept(header);
                    hasHeader = true;
                }
                if (!isHeader) {
                    rowConsumer.accept(cells, rowNumber);
                    emitted++;
                }
            }

            @Override
            public boolean hasNext(AnalysisContext context) {
                checkCancelled.run();
                return emitted < limit && (options.getDataEndRow() == null
                        || context.readRowHolder() == null
                        || context.readRowHolder().getRowIndex() + 1 < options.getDataEndRow());
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) { }
        }).headRowNumber(0).autoTrim(false).ignoreEmptyRow(false).useDefaultListener(false)
                .sheet(options.getSheetIndex()).doRead();
    }

    private static ImportCell cell(ReadCellData<?> cell, AnalysisContext context, ExcelOptions options, int column) {
        if (cell == null || cell.getType() == com.alibaba.excel.enums.CellDataTypeEnum.EMPTY) {
            return new ImportCell(options.getEmptyAsNull() ? null : "", true);
        }
        return switch (cell.getType()) {
            case NUMBER -> {
                var format = cell.getDataFormatData();
                if (format != null && DateUtil.isADateFormat(format.getIndex(), format.getFormat())) {
                    boolean use1904 = Boolean.TRUE.equals(context.readWorkbookHolder().getGlobalConfiguration().getUse1904windowing());
                    yield new ImportCell(DateUtil.getLocalDateTime(cell.getNumberValue().doubleValue(), use1904), false);
                }
                yield new ImportCell(cell.getNumberValue(), false);
            }
            case BOOLEAN -> new ImportCell(cell.getBooleanValue(), false);
            case ERROR -> throw new BusinessException("import.preview.excelCellError",
                    new Object[]{context.readRowHolder().getRowIndex() + 1, column + 1});
            default -> new ImportCell(options.getEmptyAsNull() && "".equals(cell.getStringValue())
                    ? null : cell.getStringValue(), true);
        };
    }
}
