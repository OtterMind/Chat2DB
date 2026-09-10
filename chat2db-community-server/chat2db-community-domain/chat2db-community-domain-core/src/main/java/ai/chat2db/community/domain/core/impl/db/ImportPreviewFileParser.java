package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.tools.exception.BusinessException;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public final class ImportPreviewFileParser {

    ParsedRows parse(File file, int limit, CsvOptions csvOptions) {
        return isCsv(file) ? parseCsv(file, limit, csvOptions) : parseExcel(file, limit);
    }

    private ParsedRows parseCsv(File file, int limit, CsvOptions csvOptions) {
        CsvOptions options = (csvOptions == null ? CsvOptions.defaults() : csvOptions).validate();
        try {
            int previewEndRow = options.getDataStartRow() + limit - 1;
            if (options.getDataEndRow() != null) {
                previewEndRow = Math.min(previewEndRow, options.getDataEndRow());
            }
            int parseLimit = Math.max(previewEndRow,
                    Boolean.TRUE.equals(options.getHasHeader()) ? options.getHeaderRow() : 0);
            List<Map<Integer, String>> rows = new CsvParser(options).parse(file.toPath(), parseLimit).rows();
            if (rows.isEmpty()) {
                return ParsedRows.empty();
            }
            int firstDataIndex = options.getDataStartRow() - 1;
            int dataEndIndex = Math.min(rows.size(), previewEndRow);
            List<Map<Integer, String>> data = firstDataIndex >= dataEndIndex
                    ? List.of() : rows.subList(firstDataIndex, dataEndIndex);
            if (Boolean.TRUE.equals(options.getHasHeader())) {
                int headerIndex = options.getHeaderRow() - 1;
                return headerIndex >= rows.size()
                        ? ParsedRows.empty() : new ParsedRows(rows.get(headerIndex), data, false);
            }
            return new ParsedRows(syntheticHeader(data), data, true);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CSV import preview parse failed for {}", file, e);
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
    }

    private ParsedRows parseExcel(File file, int limit) {
        try {
            ImportPreviewListener listener = new ImportPreviewListener(limit);
            EasyExcel.read(file, listener).excelType(excelType(file)).sheet().headRowNumber(1).doRead();
            List<Map<Integer, String>> rows = listener.rows();
            return rows.isEmpty() ? ParsedRows.empty()
                    : new ParsedRows(rows.get(0), rows.subList(1, rows.size()), false);
        } catch (Exception e) {
            log.warn("Excel import preview parse failed for {}", file, e);
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
    }

    private static Map<Integer, String> syntheticHeader(List<Map<Integer, String>> data) {
        int columnCount = data.stream().mapToInt(Map::size).max().orElse(0);
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int index = 0; index < columnCount; index++) {
            header.put(index, "column_" + (index + 1));
        }
        return header;
    }

    private static boolean isCsv(File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private static ExcelTypeEnum excelType(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".xls")
                ? ExcelTypeEnum.XLS : ExcelTypeEnum.XLSX;
    }

    record ParsedRows(Map<Integer, String> header, List<Map<Integer, String>> data, boolean syntheticHeader) {
        private static ParsedRows empty() {
            return new ParsedRows(Map.of(), List.of(), false);
        }
    }
}
