package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded import preview with column mapping (MYSQL-IMPORT-001). CSV preview and
 * execution share one parser/mapping contract; preview reads only the first
 * {@link #PREVIEW_ROW_LIMIT} rows and never writes.
 */
@Slf4j
@Service
public class DbImportPreviewServiceImpl implements IDbImportPreviewService {

    private static final int PREVIEW_ROW_LIMIT = 50;
    private static final int EXECUTION_BATCH_SIZE = 200;
    private static final String DEFAULT_STRATEGY = "DEFAULT";
    private static final String NULL_STRATEGY = "NULL";
    @Override
    public Map<String, Object> preview(Long dataSourceId, String databaseName, String schemaName, String tableName,
                                       File file, Map<String, Object> csvOptions) {
        ImportTarget target = resolveImportTarget(dataSourceId, databaseName, schemaName, tableName);
        ParseOutcome outcome = parseRows(file, PREVIEW_ROW_LIMIT, csvOptions);
        List<Map<Integer, ExcelParser.CellValue>> rows = outcome.rows;
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, ExcelParser.CellValue> header = outcome.header;
        List<Map<String, Object>> sourceColumns = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            ExcelParser.CellValue headerValue = header.get(i);
            String name = headerValue == null || StringUtils.isBlank(headerValue.value())
                    ? "column_" + (i + 1)
                    : headerValue.value();
            sourceNames.add(name);
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

        TargetMetadata targetMetadata = targetMetadata(target);
        List<Map<String, Object>> targetColumns = targetMetadata.columns();
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
        result.put("headerRow", outcome.firstDataRow == 1);
        result.put("sheets", outcome.sheets);
        return result;
    }

    @Override
    public Map<String, Object> execute(Long dataSourceId, String databaseName, String schemaName, String tableName,
                                       File file, Map<String, Object> csvOptions,
                                       List<Map<String, String>> mappings, String unmappedTarget,
                                       TaskExecutionContext context) {
        ImportTarget target = resolveImportTarget(dataSourceId, databaseName, schemaName, tableName);
        context.checkCancelled();
        ParseOutcome outcome = parseRows(file, Integer.MAX_VALUE, csvOptions);
        List<Map<Integer, ExcelParser.CellValue>> rows = outcome.rows;
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, ExcelParser.CellValue> header = outcome.header;
        TargetMetadata targetMetadata = targetMetadata(target);
        List<Map<String, Object>> targetColumns = targetMetadata.columns();
        String strategy = StringUtils.defaultIfBlank(unmappedTarget, DEFAULT_STRATEGY).toUpperCase(Locale.ROOT);
        if (!DEFAULT_STRATEGY.equals(strategy) && !NULL_STRATEGY.equals(strategy)) {
            throw new BusinessException("import.preview.unsupportedStrategy");
        }

        Map<Integer, String> sourceToTarget = new LinkedHashMap<>();
        for (Map<String, String> mapping : mappings == null ? List.<Map<String, String>>of() : mappings) {
            String source = mapping.get("sourceColumn");
            String mappingTarget = mapping.get("targetColumn");
            if (StringUtils.isBlank(mappingTarget)) {
                continue;
            }
            int sourceIndex = indexOfName(header, source);
            if (sourceIndex < 0) {
                // Skipped source field (no matching header) — ignore.
                continue;
            }
            sourceToTarget.put(sourceIndex, mappingTarget);
        }
        if (sourceToTarget.isEmpty()) {
            for (Map.Entry<Integer, ExcelParser.CellValue> source : header.entrySet()) {
                targetColumns.stream()
                        .map(targetColumn -> (String) targetColumn.get("name"))
                        .filter(targetName -> StringUtils.equalsIgnoreCase(targetName, source.getValue().value()))
                        .findFirst()
                        .ifPresent(targetName -> sourceToTarget.put(source.getKey(), targetName));
            }
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int skipped = 0;

        String insertSql = buildInsertSql(targetMetadata, targetColumns, sourceToTarget, strategy);
        Connection connection = Chat2DBContext.getConnection();
        boolean originalAutoCommit;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new BusinessException("import.preview.executeFailed", new Object[]{e.getMessage()}, e);
        }
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            List<RowToInsert> batch = new ArrayList<>(EXECUTION_BATCH_SIZE);
            for (int r = outcome.firstDataRow; r < rows.size(); r++) {
                context.checkCancelled();
                Map<Integer, ExcelParser.CellValue> row = rows.get(r);
                if (isEmptyRow(row)) {
                    skipped++;
                    continue;
                }
                try {
                    bindRow(statement, row, targetColumns, sourceToTarget, strategy);
                    statement.addBatch();
                    batch.add(new RowToInsert(r + 1, row));
                    if (batch.size() == EXECUTION_BATCH_SIZE) {
                        BatchResult result = executeBatch(statement, connection, batch, targetColumns,
                                sourceToTarget, strategy, errors, context);
                        success += result.success();
                        failed += result.failed();
                    }
                } catch (SQLException e) {
                    failed++;
                    errors.add(errorEntry(r + 1, null, e.getMessage()));
                }
            }
            if (!batch.isEmpty()) {
                BatchResult result = executeBatch(statement, connection, batch, targetColumns,
                        sourceToTarget, strategy, errors, context);
                success += result.success();
                failed += result.failed();
            }
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new BusinessException("import.preview.executeFailed", new Object[]{e.getMessage()}, e);
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
                log.warn("Could not restore auto-commit after mapped import", ignored);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRows", Math.max(0, rows.size() - outcome.firstDataRow));
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("skippedCount", skipped);
        result.put("errors", errors);
        return result;
    }

    private static boolean isNotAutoIncrement(Map<String, Object> target) {
        return !Boolean.TRUE.equals(target.get("autoIncrement"));
    }

    private static boolean isEmptyRow(Map<Integer, ExcelParser.CellValue> row) {
        return row == null || row.isEmpty() || row.values().stream()
                .allMatch(value -> value == null || StringUtils.isEmpty(value.value()));
    }

    private static BatchResult executeBatch(PreparedStatement statement, Connection connection,
            List<RowToInsert> batch, List<Map<String, Object>> targetColumns, Map<Integer, String> sourceToTarget,
            String strategy, List<Map<String, Object>> errors, TaskExecutionContext context) throws SQLException {
        context.checkCancelled();
        try {
            statement.executeBatch();
            connection.commit();
            int count = batch.size();
            batch.clear();
            return new BatchResult(count, 0);
        } catch (SQLException batchFailure) {
            rollbackQuietly(connection);
            statement.clearBatch();
            int success = 0;
            int failed = 0;
            for (RowToInsert item : batch) {
                context.checkCancelled();
                try {
                    bindRow(statement, item.row(), targetColumns, sourceToTarget, strategy);
                    statement.executeUpdate();
                    connection.commit();
                    success++;
                } catch (SQLException rowFailure) {
                    rollbackQuietly(connection);
                    failed++;
                    errors.add(errorEntry(item.sourceRow(), null, rowFailure.getMessage()));
                }
            }
            batch.clear();
            return new BatchResult(success, failed);
        }
    }

    private static void bindRow(PreparedStatement statement, Map<Integer, ExcelParser.CellValue> row,
            List<Map<String, Object>> targetColumns, Map<Integer, String> sourceToTarget, String strategy)
            throws SQLException {
        int paramIndex = 1;
        for (Map<String, Object> target : insertColumns(targetColumns, sourceToTarget, strategy)) {
            String targetName = (String) target.get("name");
            Integer sourceIndex = sourceToTarget.entrySet().stream()
                    .filter(entry -> StringUtils.equals(entry.getValue(), targetName))
                    .map(Map.Entry::getKey).findFirst().orElse(-1);
            if (sourceIndex < 0) {
                if (NULL_STRATEGY.equals(strategy)) {
                    statement.setNull(paramIndex, Types.NULL);
                } else {
                    statement.setObject(paramIndex, null, Types.NULL);
                }
            } else {
                ExcelParser.CellValue cell = row.get(sourceIndex);
                String value = cell == null ? null : cell.value();
                if (value == null || (value.isEmpty() && "empty".equals(cell == null ? "" : cell.type()))) {
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
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original import failure.
        }
    }

    private record RowToInsert(int sourceRow, Map<Integer, ExcelParser.CellValue> row) {
    }

    private record BatchResult(int success, int failed) {
    }

    private static Map<String, Object> errorEntry(int row, String column, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("row", row);
        entry.put("column", column);
        entry.put("message", message);
        return entry;
    }

    private static int indexOfName(Map<Integer, ExcelParser.CellValue> header, String name) {
        if (StringUtils.isBlank(name)) {
            return -1;
        }
        for (Map.Entry<Integer, ExcelParser.CellValue> entry : header.entrySet()) {
            if (StringUtils.equalsIgnoreCase(entry.getValue().value(), name)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private static String buildInsertSql(TargetMetadata targetMetadata, List<Map<String, Object>> targetColumns,
                                        Map<Integer, String> sourceToTarget, String strategy) {
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        boolean first = true;
        for (Map<String, Object> target : insertColumns(targetColumns, sourceToTarget, strategy)) {
            String name = (String) target.get("name");
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
        String qualifiedTableName = Chat2DBContext.getDbMetaData()
                .getQualifiedTableName(targetMetadata.databaseName(), targetMetadata.schemaName(), targetMetadata.tableName());
        return "INSERT INTO " + qualifiedTableName
                + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    private static List<Map<String, Object>> insertColumns(List<Map<String, Object>> targetColumns,
            Map<Integer, String> sourceToTarget, String strategy) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> target : targetColumns) {
            String name = (String) target.get("name");
            boolean mapped = sourceToTarget.values().stream().anyMatch(t -> StringUtils.equals(t, name));
            if (mapped || (NULL_STRATEGY.equals(strategy) && isNotAutoIncrement(target))) {
                columns.add(target);
            }
        }
        return columns;
    }

    private static void setValueByType(PreparedStatement statement, int index, String value, String dataType)
            throws SQLException {
        String type = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
        if (type.contains("INT") || type.contains("DECIMAL") || type.contains("NUMERIC")
                || type.contains("FLOAT") || type.contains("DOUBLE") || type.contains("YEAR")) {
            try {
                if (type.contains("DECIMAL") || type.contains("NUMERIC")) {
                    statement.setBigDecimal(index, new BigDecimal(value.trim()));
                } else if (type.contains("FLOAT") || type.contains("DOUBLE") || type.contains("REAL")) {
                    statement.setDouble(index, Double.parseDouble(value.trim()));
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
            statement.setString(index, sanitizeFormula(value));
        }
    }

    private static String sanitizeFormula(String value) {
        if (StringUtils.isEmpty(value)) {
            return value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r' ? "'" + value : value;
    }

    static ImportTarget resolveImportTarget(Long dataSourceId, String requestedDatabaseName,
                                            String requestedSchemaName, String requestedTableName) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            throw new BusinessException("import.preview.connectionRequired");
        }
        if (dataSourceId == null || !dataSourceId.equals(connectInfo.getDataSourceId())) {
            throw new BusinessException("import.preview.connectionMismatch");
        }
        String databaseName = trustedMetadataName("database", connectInfo.getDatabaseName());
        String schemaName = trustedOptionalMetadataName("schema", connectInfo.getSchemaName());
        String tableName = requestedMetadataName("table", requestedTableName);
        rejectMismatch("database", requestedDatabaseName, databaseName);
        rejectMismatch("schema", requestedSchemaName, schemaName);
        return new ImportTarget(databaseName, schemaName, tableName);
    }

    private static void rejectMismatch(String field, String requested, String trusted) {
        if (StringUtils.isBlank(requested)) {
            return;
        }
        String requestedName = requestedMetadataName(field, requested);
        if (!StringUtils.equals(requestedName, trusted)) {
            throw new BusinessException("import.preview.connectionMismatch");
        }
    }

    private static String trustedMetadataName(String field, String value) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("import.preview.connectionRequired");
        }
        return requestedMetadataName(field, value);
    }

    private static String trustedOptionalMetadataName(String field, String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return requestedMetadataName(field, value);
    }

    private static String requestedMetadataName(String field, String value) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null) {
            throw new BusinessException("import.preview.invalidMetadata");
        }
        if (containsMetadataWildcard(trimmed)) {
            throw new BusinessException("import.preview.invalidMetadata");
        }
        return trimmed;
    }

    private static boolean containsMetadataWildcard(String value) {
        return StringUtils.containsAny(value, "%*?");
    }

    private static TargetMetadata targetMetadata(ImportTarget target) {
        Connection connection = Chat2DBContext.getConnection();
        String tableName = trustedTableName(connection, target);
        List<Map<String, Object>> columns = Chat2DBContext.getDbMetaData().columns(connection,
                        new TableMetadataRequest(target.databaseName(), target.schemaName(), tableName)).stream()
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
        return new TargetMetadata(target.databaseName(), target.schemaName(), tableName, columns);
    }

    private static String trustedTableName(Connection connection, ImportTarget target) {
        List<Table> tables = Chat2DBContext.getDbMetaData()
                .tables(connection, new TablesRequest(target.databaseName(), target.schemaName(), null));
        return tables.stream()
                .filter(table -> table != null && StringUtils.equals(table.getName(), target.tableName()))
                .findFirst()
                .map(Table::getName)
                .orElseThrow(() -> new BusinessException("import.preview.tableNotFound"));
    }

    private record ParseOutcome(List<Map<Integer, ExcelParser.CellValue>> rows,
                                Map<Integer, ExcelParser.CellValue> header,
                                int firstDataRow, List<Map<String, Object>> sheets) {
    }

    record ImportTarget(String databaseName, String schemaName, String tableName) {
    }

    private record TargetMetadata(String databaseName, String schemaName, String tableName,
                                  List<Map<String, Object>> columns) {
    }

    private static boolean isCsv(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv");
    }

    private static ParseOutcome parseRows(File file, int limit,
                                          Map<String, Object> csvOptions) {
        if (file != null && file.isFile() && file.canRead() && isCsv(file.getName())) {
            CsvOptions options = CsvOptions.fromMap(csvOptions);
            CsvParser parser = new CsvParser(options);
            CsvParser.CsvResult result;
            try (InputStream inputStream = Files.newInputStream(file.toPath())) {
                result = parser.parse(inputStream, limit);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
            }
            List<Map<Integer, ExcelParser.CellValue>> typedRows = new ArrayList<>();
            for (Map<Integer, CsvParser.CsvCell> row : result.cells()) {
                Map<Integer, ExcelParser.CellValue> typed = new LinkedHashMap<>();
                row.forEach((k, v) -> typed.put(k,
                        new ExcelParser.CellValue(v == null ? null : v.value(),
                                v == null || v.value() == null ? "empty" : "string")));
                typedRows.add(typed);
            }
            Map<Integer, ExcelParser.CellValue> header;
            int firstDataRow;
            if (result.headerRowCount() > 0 && !typedRows.isEmpty()) {
                header = typedRows.get(0);
                firstDataRow = 1;
            } else {
                header = new LinkedHashMap<>();
                int columns = typedRows.isEmpty() ? 0 : typedRows.get(0).size();
                for (int i = 0; i < columns; i++) {
                    header.put(i, new ExcelParser.CellValue("column_" + (i + 1), "string"));
                }
                firstDataRow = 0;
            }
            return new ParseOutcome(typedRows, header, firstDataRow, List.of());
        }
        throw new BusinessException("import.preview.unsupportedFile");
    }
}
