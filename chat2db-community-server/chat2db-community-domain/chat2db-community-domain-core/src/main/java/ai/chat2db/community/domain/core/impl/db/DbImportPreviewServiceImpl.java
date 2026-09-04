package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.core.impl.task.imports.ImportTargetMetadataGuard;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded import preview with column mapping. The preview reads only the first
 * {@link #PREVIEW_ROW_LIMIT} rows and never writes.
 */
@Service
public class DbImportPreviewServiceImpl implements IDbImportPreviewService {

    private static final int PREVIEW_ROW_LIMIT = 50;

    @Override
    public Map<String, Object> preview(Long dataSourceId, String databaseName, String schemaName, String tableName,
                                       File file, Map<String, Object> importOptions) {
        TableMetadataRequest tableRequest = resolveTarget(dataSourceId, databaseName, schemaName, tableName);
        ParseOutcome outcome = parseRows(file, PREVIEW_ROW_LIMIT, importOptions);
        List<Map<Integer, ExcelParser.CellValue>> rows = outcome.rows;
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, ExcelParser.CellValue> header = outcome.header;
        List<Map<String, Object>> sourceColumns = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        List<String> invalidHeaders = new ArrayList<>();
        Set<String> uniqueHeaders = new HashSet<>();
        Set<String> duplicateHeaders = new HashSet<>();
        for (int i = 0; i < header.size(); i++) {
            ExcelParser.CellValue headerValue = header.get(i);
            String name = headerValue == null || StringUtils.isBlank(headerValue.value())
                    ? "column_" + (i + 1)
                    : headerValue.value();
            sourceNames.add(name);
            if (headerValue == null || StringUtils.isBlank(headerValue.value())) {
                invalidHeaders.add(name);
            }
            String normalizedHeader = name.toUpperCase(Locale.ROOT);
            if (!uniqueHeaders.add(normalizedHeader)) {
                duplicateHeaders.add(name);
            }
            List<Map<String, Object>> samples = new ArrayList<>();
            for (int r = outcome.firstDataRow; r < rows.size(); r++) {
                ExcelParser.CellValue value = rows.get(r).get(i);
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("value", value == null ? "" : (value.value() == null ? "" : value.value()));
                sample.put("type", value == null ? "empty" : value.type());
                samples.add(sample);
            }
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", name);
            column.put("sampleValues", samples);
            sourceColumns.add(column);
        }

        List<Map<String, Object>> targetColumns = targetColumns(tableRequest);
        List<Map<String, String>> suggested = new ArrayList<>();
        for (String source : sourceNames) {
            targetColumns.stream()
                    .filter(tc -> StringUtils.equalsIgnoreCase((String) tc.get("name"), source))
                    .findFirst()
                    .ifPresent(tc -> {
                        Map<String, String> mapping = new LinkedHashMap<>();
                        mapping.put("sourceColumn", source);
                        mapping.put("targetColumn", (String) tc.get("name"));
                        suggested.add(mapping);
                    });
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceColumns", sourceColumns);
        result.put("targetColumns", targetColumns);
        result.put("suggestedMapping", suggested);
        result.put("previewLimit", PREVIEW_ROW_LIMIT);
        result.put("previewRows", Math.max(0, rows.size() - outcome.firstDataRow));
        result.put("skippedCount", outcome.skippedCount);
        result.put("headerRow", outcome.firstDataRow == 1);
        result.put("sheets", outcome.sheets);
        result.put("selectedSheet", selectedSheet(outcome));
        result.put("startRow", outcome.config.startRow());
        result.put("endRow", outcome.config.endRow());
        result.put("invalidHeaders", invalidHeaders);
        result.put("duplicateHeaders", new ArrayList<>(duplicateHeaders));
        result.put("hasMoreRows", outcome.hasMoreRows);
        return result;
    }

    private static TableMetadataRequest resolveTarget(Long dataSourceId, String databaseName, String schemaName,
            String tableName) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        Connection connection = Chat2DBContext.getConnection();
        return ImportTargetMetadataGuard.resolve(metaData, connection, connectInfo, dataSourceId, databaseName,
                schemaName, tableName);
    }

    private static List<Map<String, Object>> targetColumns(TableMetadataRequest request) {
        Connection connection = Chat2DBContext.getConnection();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        return ImportTargetMetadataGuard.exactTableColumns(metaData, connection, request).stream()
                .map(column -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", column.getName());
                    map.put("dataType", column.getColumnType());
                    map.put("nullable", column.getNullable() != null && column.getNullable() == 1);
                    map.put("autoIncrement", Boolean.TRUE.equals(column.getAutoIncrement()));
                    map.put("defaultValue", column.getDefaultValue());
                    return map;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private record ParseOutcome(List<Map<Integer, ExcelParser.CellValue>> rows,
                                Map<Integer, ExcelParser.CellValue> header,
                                int firstDataRow, List<Map<String, Object>> sheets,
                                ExcelImportConfig config, boolean hasMoreRows, long skippedCount) {
    }

    private static String selectedSheet(ParseOutcome outcome) {
        if (StringUtils.isNotBlank(outcome.config.sheetName())) {
            return outcome.config.sheetName();
        }
        if (outcome.sheets.isEmpty()) {
            return null;
        }
        return (String) outcome.sheets.get(0).get("name");
    }

    private static ParseOutcome parseRows(File file, int limit, Map<String, Object> importOptions) {
        if (file == null || !file.isFile() || !file.canRead()) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        Map<String, Object> options = importOptions == null ? Map.of() : importOptions;
        if (ExcelParser.isExcel(file.getName())) {
            ExcelImportConfig config = ExcelImportConfig.from(options);
            int readLimit = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;
            ExcelParser.ExcelResult result = ExcelParser.parse(file, file.getName(), config, readLimit);
            List<Map<Integer, ExcelParser.CellValue>> rows = result.rows();
            boolean hasMoreRows = limit != Integer.MAX_VALUE
                    && Math.max(0, rows.size() - result.headerRowCount()) > limit;
            if (hasMoreRows) {
                rows = rows.subList(0, result.headerRowCount() + limit);
            }
            Map<Integer, ExcelParser.CellValue> header;
            int firstDataRow;
            if (result.headerRowCount() > 0 && !rows.isEmpty()) {
                header = rows.get(0);
                firstDataRow = 1;
            } else {
                header = new LinkedHashMap<>();
                int columns = rows.isEmpty() ? 0 : rows.get(0).size();
                for (int i = 0; i < columns; i++) {
                    header.put(i, new ExcelParser.CellValue("column_" + (i + 1), "string"));
                }
                firstDataRow = 0;
            }
            return new ParseOutcome(rows, header, firstDataRow, ExcelParser.sheets(file, file.getName()),
                    config, hasMoreRows, result.skippedRowCount());
        }
        throw new BusinessException("import.preview.unsupportedFile");
    }
}
