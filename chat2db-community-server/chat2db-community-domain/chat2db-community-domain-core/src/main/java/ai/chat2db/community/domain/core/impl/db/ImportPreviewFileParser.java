package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.model.task.ExcelOptions;
import ai.chat2db.community.domain.api.model.task.JsonOptions;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ExcelImportReader;
import ai.chat2db.community.domain.core.impl.task.imports.reader.JsonImportReader;
import ai.chat2db.community.domain.core.impl.task.imports.reader.SourceColumnName;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ImportCell;
import java.util.ArrayList;
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
        return parse(file, limit, csvOptions, null, null);
    }

    ParsedRows parse(File file, int limit, CsvOptions csvOptions, ExcelOptions excelOptions, JsonOptions jsonOptions) {
        if (isCsv(file)) return parseCsv(file, limit, csvOptions);
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
            return parseJson(file, limit, jsonOptions == null ? new JsonOptions() : jsonOptions);
        }
        return parseExcel(file, limit, excelOptions == null ? new ExcelOptions() : excelOptions);
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
            throw new BusinessException("import.preview.parseFailed", null, e);
        }
    }

    private ParsedRows parseExcel(File file, int limit, ExcelOptions options) {
        Map<Integer, String> header = new LinkedHashMap<>();
        List<Map<Integer, String>> rows = new ArrayList<>();
        try {
            ExcelImportReader.read(file, options, limit, 0, header::putAll, (cells, number) -> {
                Map<Integer, String> row = new LinkedHashMap<>();
                cells.forEach((index, cell) -> row.put(index, cell.display()));
                rows.add(row);
            }, () -> { });
            return new ParsedRows(options.getHasHeader() ? header : syntheticHeader(rows), rows,
                    !options.getHasHeader());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidExcelFile", null, e);
        }
    }

    private ParsedRows parseJson(File file, int limit, JsonOptions options) {
        List<Map<String, ImportCell>> records = new ArrayList<>();
        Map<String, Integer> indexes = new LinkedHashMap<>();
        JsonImportReader.read(file, options, limit, (record, number) -> {
            if (records.size() < limit) {
                records.add(record);
            }
            record.keySet().forEach(name -> indexes.computeIfAbsent(name, ignored -> indexes.size()));
        }, () -> { }, true);
        Map<Integer, String> header = new LinkedHashMap<>();
        indexes.forEach((name, index) -> header.put(index, name));
        List<Map<Integer, String>> rows = new ArrayList<>();
        for (Map<String, ImportCell> record : records) {
            Map<Integer, String> row = new LinkedHashMap<>();
            indexes.forEach((name, index) -> row.put(index, record.containsKey(name) ? record.get(name).display() : null));
            rows.add(row);
        }
        return new ParsedRows(header, rows, false);
    }

    private static Map<Integer, String> syntheticHeader(List<Map<Integer, String>> data) {
        int columnCount = data.stream().mapToInt(Map::size).max().orElse(0);
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int index = 0; index < columnCount; index++) {
            header.put(index, SourceColumnName.of(index));
        }
        return header;
    }

    private static boolean isCsv(File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    record ParsedRows(Map<Integer, String> header, List<Map<Integer, String>> data, boolean syntheticHeader) {
        private static ParsedRows empty() {
            return new ParsedRows(Map.of(), List.of(), false);
        }
    }
}
