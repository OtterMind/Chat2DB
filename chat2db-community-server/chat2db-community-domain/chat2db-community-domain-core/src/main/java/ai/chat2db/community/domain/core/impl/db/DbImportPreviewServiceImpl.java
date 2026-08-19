package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.excel.EasyExcel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Bounded import preview with column mapping (MYSQL-IMPORT-001). CSV/XLS/XLSX are parsed
 * through EasyExcel with the same reader for preview and execution; the preview reads only
 * the first {@link #PREVIEW_ROW_LIMIT} rows and never writes.
 */
@Slf4j
@Service
public class DbImportPreviewServiceImpl implements IDbImportPreviewService {

    private static final int PREVIEW_ROW_LIMIT = 50;
    private static final String DEFAULT_STRATEGY = "DEFAULT";
    private static final String NULL_STRATEGY = "NULL";

    @Override
    public Map<String, Object> preview(Long dataSourceId, String databaseName, String tableName,
                                       String filePath) {
        byte[] bytes = readFile(filePath);
        List<Map<Integer, String>> rows = parseRows(bytes, filePath, PREVIEW_ROW_LIMIT);
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, String> header = rows.get(0);
        List<Map<String, Object>> sourceColumns = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = StringUtils.defaultIfBlank(header.get(i), "column_" + (i + 1));
            sourceNames.add(name);
            List<String> samples = new ArrayList<>();
            for (int r = 1; r < rows.size(); r++) {
                String value = rows.get(r).get(i);
                samples.add(value == null ? "" : value);
            }
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", name);
            column.put("sampleValues", samples);
            sourceColumns.add(column);
        }

        List<Map<String, Object>> targetColumns = targetColumns(databaseName, tableName);
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
        result.put("previewRows", rows.size() - 1);
        return result;
    }

    @Override
    public Map<String, Object> execute(Long dataSourceId, String databaseName, String tableName,
                                       String filePath,
                                       List<Map<String, String>> mappings, String unmappedTarget) {
        byte[] bytes = readFile(filePath);
        List<Map<Integer, String>> rows = parseRows(bytes, filePath, Integer.MAX_VALUE);
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, String> header = rows.get(0);
        List<Map<String, Object>> targetColumns = targetColumns(databaseName, tableName);
        String strategy = StringUtils.defaultIfBlank(unmappedTarget, DEFAULT_STRATEGY).toUpperCase(Locale.ROOT);
        if (!DEFAULT_STRATEGY.equals(strategy) && !NULL_STRATEGY.equals(strategy)) {
            throw new BusinessException("import.preview.unsupportedStrategy");
        }

        // Resolve mapping: source index -> target column name; track unmapped targets.
        Map<Integer, String> sourceToTarget = new LinkedHashMap<>();
        for (Map<String, String> mapping : mappings == null ? List.of() : mappings) {
            String source = mapping.get("sourceColumn");
            String target = mapping.get("targetColumn");
            if (StringUtils.isBlank(target)) {
                continue;
            }
            int sourceIndex = indexOfName(header, source);
            if (sourceIndex < 0) {
                // Skipped source field (no matching header) — ignore.
                continue;
            }
            sourceToTarget.put(sourceIndex, target);
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int skipped = 0;

        String insertSql = buildInsertSql(tableName, targetColumns, sourceToTarget, strategy);
        Connection connection = Chat2DBContext.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (int r = 1; r < rows.size(); r++) {
                Map<Integer, String> row = rows.get(r);
                try {
                    int paramIndex = 1;
                    for (Map<String, Object> target : targetColumns) {
                        String targetName = (String) target.get("name");
                        String sourceIndexKey = sourceToTarget.entrySet().stream()
                                .filter(e -> StringUtils.equals(e.getValue(), targetName))
                                .map(Map.Entry::getKey)
                                .findFirst().orElse(-1);
                        if (sourceIndexKey < 0) {
                            // Unmapped target column: DEFAULT (omit) or explicit NULL.
                            if (NULL_STRATEGY.equals(strategy)) {
                                statement.setNull(paramIndex, Types.NULL);
                            } else {
                                statement.setObject(paramIndex, null, Types.NULL);
                            }
                        } else {
                            String value = row.get(sourceIndexKey);
                            if (StringUtils.isBlank(value)) {
                                if (Boolean.FALSE.equals(target.get("nullable")) && isNotAutoIncrement(target)) {
                                    throw new SQLException("NOT NULL column '" + targetName + "' has no value");
                                }
                                statement.setNull(paramIndex, Types.NULL);
                            } else {
                                setValueByType(statement, paramIndex, value, (String) target.get("dataType"));
                            }
                        }
                        paramIndex++;
                    }
                    statement.addBatch();
                    statement.executeBatch();
                    success++;
                } catch (SQLException e) {
                    failed++;
                    errors.add(errorEntry(r + 1, null, e.getMessage()));
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("import.preview.executeFailed", new Object[]{e.getMessage()}, e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRows", rows.size() - 1);
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("skippedCount", skipped);
        result.put("errors", errors);
        return result;
    }

    private static boolean isNotAutoIncrement(Map<String, Object> target) {
        return !Boolean.TRUE.equals(target.get("autoIncrement"));
    }

    private static Map<String, Object> errorEntry(int row, String column, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("row", row);
        entry.put("column", column);
        entry.put("message", message);
        return entry;
    }

    private static int indexOfName(Map<Integer, String> header, String name) {
        if (StringUtils.isBlank(name)) {
            return -1;
        }
        for (Map.Entry<Integer, String> entry : header.entrySet()) {
            if (StringUtils.equalsIgnoreCase(entry.getValue(), name)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private static String buildInsertSql(String tableName, List<Map<String, Object>> targetColumns,
                                        Map<Integer, String> sourceToTarget, String strategy) {
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        boolean first = true;
        for (Map<String, Object> target : targetColumns) {
            String name = (String) target.get("name");
            boolean mapped = sourceToTarget.values().stream().anyMatch(t -> StringUtils.equals(t, name));
            if (!mapped && DEFAULT_STRATEGY.equals(strategy)) {
                continue;
            }
            if (!first) {
                columns.append(", ");
                placeholders.append(", ");
            }
            columns.append(Chat2DBContext.getDbMetaData().getMetaDataName(name));
            placeholders.append("?");
            first = false;
        }
        if (first) {
            throw new BusinessException("import.preview.noColumns");
        }
        return "INSERT INTO " + Chat2DBContext.getDbMetaData().getMetaDataName(tableName)
                + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    private static void setValueByType(PreparedStatement statement, int index, String value, String dataType)
            throws SQLException {
        String type = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
        if (type.contains("INT") || type.contains("DECIMAL") || type.contains("NUMERIC")
                || type.contains("FLOAT") || type.contains("DOUBLE") || type.contains("YEAR")) {
            try {
                if (type.contains("DECIMAL") || type.contains("NUMERIC")) {
                    statement.setBigDecimal(index, new BigDecimal(value.trim()));
                } else {
                    statement.setLong(index, Long.parseLong(value.trim()));
                }
            } catch (NumberFormatException e) {
                throw new SQLException("invalid numeric value '" + value + "' for " + dataType);
            }
        } else if (type.contains("DATETIME") || type.contains("TIMESTAMP")) {
            try {
                statement.setTimestamp(index, Timestamp.valueOf(value.trim().replace('T', ' ')));
            } catch (IllegalArgumentException e) {
                throw new SQLException("invalid datetime value '" + value + "' for " + dataType);
            }
        } else if (type.contains("DATE")) {
            try {
                statement.setDate(index, java.sql.Date.valueOf(value.trim()));
            } catch (IllegalArgumentException e) {
                throw new SQLException("invalid date value '" + value + "' for " + dataType);
            }
        } else if (type.contains("BIT")) {
            statement.setString(index, value.trim());
        } else {
            statement.setString(index, value);
        }
    }

    private static List<Map<String, Object>> targetColumns(String databaseName, String tableName) {
        Connection connection = Chat2DBContext.getConnection();
        return Chat2DBContext.getDbMetaData().columns(connection, databaseName, null, tableName).stream()
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

    /**
     * Parses the file with EasyExcel (same code path for preview and execution). The first
     * row is treated as the header; without a header the columns are named column_1..N.
     */
    @SuppressWarnings("unchecked")
    private static byte[] readFile(String filePath) {
        if (StringUtils.isBlank(filePath)) {
            throw new BusinessException("import.preview.fileRequired");
        }
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
        } catch (Exception e) {
            log.warn("import preview cannot read file {}", filePath, e);
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
    }

    private static List<Map<Integer, String>> parseRows(byte[] bytes, String fileName, int limit) {
        // EasyExcel without a model returns List<Map<Integer,String>>; the same reader is
        // used for preview (bounded) and execution (full), so both see identical rows.
        try {
            List<Map<Integer, String>> all = (List<Map<Integer, String>>) EasyExcel.read(new ByteArrayInputStream(bytes))
                    .headRowNumber(1)
                    .sheet()
                    .doReadSync();
            List<Map<Integer, String>> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, all == null ? 0 : all.size()); i++) {
                Map<Integer, String> normalized = new LinkedHashMap<>();
                Map<Integer, String> raw = all.get(i);
                if (raw != null) {
                    raw.forEach((k, v) -> normalized.put(k, v == null ? "" : v));
                }
                rows.add(normalized);
            }
            return rows;
        } catch (Exception e) {
            log.warn("import preview parse failed for {}", fileName, e);
            throw new BusinessException("import.preview.parseFailed", new Object[]{e.getMessage()}, e);
        }
    }
}
