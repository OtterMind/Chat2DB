package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.model.ai.DataSourceToolData;
import ai.chat2db.community.domain.api.model.ai.DatabaseToolData;
import ai.chat2db.community.domain.api.model.ai.SchemaToolData;
import ai.chat2db.community.domain.api.model.ai.SqlToolData;
import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.ai.TableSchemaToolData;
import ai.chat2db.community.domain.api.model.ai.TableToolData;
import ai.chat2db.community.domain.api.model.ai.Text2SqlToolData;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class AiToolResultConverter {

    // AI preview limit only affects the serialized tool payload; it is not a database execution limit.
    private static final int AI_SQL_PREVIEW_ROW_LIMIT = 50;

    public AiToolOutput<DataSourceToolData> fromDataSources(List<WorkspaceDataSource> dataSources) {
        List<DataSourceToolData.Item> items = emptyIfNull(dataSources).stream()
                .filter(Objects::nonNull)
                .map(dataSource -> new DataSourceToolData.Item(
                        dataSource.getId(),
                        StringUtils.defaultIfBlank(dataSource.getAlias(), "(unnamed)"),
                        dataSource.getType(),
                        dataSource.getEnvType()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No datasources found." : "Found " + items.size() + " datasource(s).";
        return new AiToolOutput<>(summary, new DataSourceToolData(items));
    }

    public AiToolOutput<TableToolData> fromTables(List<SimpleTable> tables) {
        List<TableToolData.Item> items = emptyIfNull(tables).stream()
                .filter(Objects::nonNull)
                .map(table -> new TableToolData.Item(
                        StringUtils.defaultString(table.getName(), "(unnamed)"),
                        StringUtils.defaultIfBlank(table.getTableType(), "TABLE"),
                        table.getComment()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No tables found." : "Found " + items.size() + " table(s).";
        return new AiToolOutput<>(summary, new TableToolData(items));
    }

    public AiToolOutput<DatabaseToolData> fromDatabases(List<Database> databases) {
        List<DatabaseToolData.Item> items = emptyIfNull(databases).stream()
                .filter(Objects::nonNull)
                .map(database -> new DatabaseToolData.Item(
                        StringUtils.defaultString(database.getName(), "(unnamed)"),
                        database.isSystem(),
                        database.getComment()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No databases found." : "Found " + items.size() + " database(s).";
        return new AiToolOutput<>(summary, new DatabaseToolData(items));
    }

    public AiToolOutput<SchemaToolData> fromSchemas(List<Schema> schemas) {
        List<SchemaToolData.Item> items = emptyIfNull(schemas).stream()
                .filter(Objects::nonNull)
                .map(schema -> new SchemaToolData.Item(
                        StringUtils.defaultString(schema.getName(), "(unnamed)"),
                        schema.isSystem(),
                        schema.getComment()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No schemas found." : "Found " + items.size() + " schema(s).";
        return new AiToolOutput<>(summary, new SchemaToolData(items));
    }

    public AiToolOutput<SqlToolData> fromExecuteResult(List<ExecuteResponse> executeResponses) {
        List<SqlToolData.ResultSet> items = new ArrayList<>();
        int index = 1;
        for (ExecuteResponse response : emptyIfNull(executeResponses)) {
            items.add(executeResponseData(index++, response));
        }
        String summary = items.isEmpty()
                ? "SQL executed successfully with no result."
                : "SQL executed successfully with " + items.size() + " result set(s).";
        return new AiToolOutput<>(summary, new SqlToolData(items));
    }

    public AiToolOutput<TableSchemaToolData> fromTableSchemas(List<TableSchemaResult> schemaResults) {
        List<TableSchemaToolData.Item> items = emptyIfNull(schemaResults).stream()
                .filter(Objects::nonNull)
                .map(result -> new TableSchemaToolData.Item(
                        result.getTableName(),
                        buildRichTableSchema(result.getTableName(), result.getDdl(), result.getTable())))
                .collect(Collectors.toList());
        String summary = items.isEmpty()
                ? "No table schema found."
                : "Fetched schema for " + items.size() + " table(s).";
        return new AiToolOutput<>(summary, new TableSchemaToolData(items));
    }

    public AiToolOutput<Text2SqlToolData> fromText2Sql(String sql) {
        return new AiToolOutput<>(
                "SQL generated successfully.",
                new Text2SqlToolData(StringUtils.defaultString(sql)));
    }

    static SqlToolData.ResultSet executeResponseData(int index, ExecuteResponse result) {
        SqlToolData.ResultSet item = new SqlToolData.ResultSet();
        item.setResultIndex(index);
        if (Objects.isNull(result)) {
            item.setSuccess(Boolean.FALSE);
            item.setMessage("Empty result.");
            item.setText("Empty result.");
            return item;
        }
        item.setSuccess(Boolean.TRUE.equals(result.getSuccess()));
        item.setSqlType(result.getSqlType());
        item.setDurationMs(result.getDuration());
        item.setUpdateCount(result.getUpdateCount());
        item.setMessage(result.getMessage());
        item.setDescription(result.getDescription());
        item.setHasNextPage(result.getHasNextPage());
        int rowCount = result.getDataList() == null ? 0 : result.getDataList().size();
        int previewRowCount = Math.min(rowCount, AI_SQL_PREVIEW_ROW_LIMIT);
        item.setRowCount(rowCount);
        item.setPreviewRowCount(previewRowCount);
        item.setRowsTruncated(rowCount > previewRowCount);
        item.setColumns(columnNames(result.getHeaderList()));
        item.setRows(rowPreviewRows(result.getHeaderList(), result.getDataList()));
        item.setRowCellMetadata(rowPreviewCellMetadata(result.getHeaderList(), result.getDataList()));
        item.setText(formatExecuteResponse(result));
        return item;
    }

    static List<String> columnNames(List<Header> headers) {
        if (CollectionUtils.isEmpty(headers)) {
            return Collections.emptyList();
        }
        return headers.stream()
                .map(header -> StringUtils.defaultIfBlank(header.getName(), header.getColumnName()))
                .map(name -> StringUtils.defaultIfBlank(name, "col"))
                .collect(Collectors.toList());
    }

    static List<List<Object>> rowPreviewRows(List<Header> headers, List<List<ResultCell>> rows) {
        if (CollectionUtils.isEmpty(headers) || CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        List<String> headerNames = columnNames(headers);
        int rowCount = Math.min(rows.size(), AI_SQL_PREVIEW_ROW_LIMIT);
        List<List<Object>> result = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            List<ResultCell> row = rows.get(i);
            List<Object> rowData = new ArrayList<>(headerNames.size());
            for (int c = 0; c < headerNames.size(); c++) {
                ResultCell cell = row != null && c < row.size() ? row.get(c) : null;
                rowData.add(cellValue(cell));
            }
            result.add(rowData);
        }
        return result;
    }

    static List<List<SqlToolData.CellMetadata>> rowPreviewCellMetadata(List<Header> headers, List<List<ResultCell>> rows) {
        if (CollectionUtils.isEmpty(headers) || CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        List<String> headerNames = columnNames(headers);
        int rowCount = Math.min(rows.size(), AI_SQL_PREVIEW_ROW_LIMIT);
        List<List<SqlToolData.CellMetadata>> result = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            List<ResultCell> row = rows.get(i);
            List<SqlToolData.CellMetadata> rowData = new ArrayList<>(headerNames.size());
            for (int c = 0; c < headerNames.size(); c++) {
                ResultCell cell = row != null && c < row.size() ? row.get(c) : null;
                rowData.add(cellMetadata(cell));
            }
            result.add(rowData);
        }
        return result;
    }

    private static Object cellValue(ResultCell cell) {
        if (cell == null) {
            return null;
        }
        JsonSafeRawValue rawValue = jsonSafeRawValue(cell);
        if (rawValue.safe) {
            return rawValue.value;
        }
        return cell.getValue();
    }

    private static SqlToolData.CellMetadata cellMetadata(ResultCell cell) {
        if (cell == null || jsonSafeRawValue(cell).safe || isSqlNull(cell)) {
            return null;
        }
        return new SqlToolData.CellMetadata(
                Boolean.FALSE,
                cell.getValue(),
                cell.isLargeValue(),
                cell.getLargeValueId(),
                cell.getValueType(),
                cell.getSqlType(),
                cell.getColumnType(),
                cell.getSizeBytes(),
                cell.getSizeChars(),
                cell.getLoadedBytes(),
                cell.getLoadedChars(),
                cell.isTruncated(),
                cell.getUnsupportedReason(),
                rawValueUnavailableReason(cell));
    }

    private static JsonSafeRawValue jsonSafeRawValue(ResultCell cell) {
        Object rawValue = cell.getRawValue();
        if (rawValue == null || cell.isLargeValue() || cell.isTruncated()) {
            return JsonSafeRawValue.unsafe();
        }
        return jsonSafeValue(rawValue, new IdentityHashMap<>());
    }

    private static JsonSafeRawValue jsonSafeValue(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return JsonSafeRawValue.safe(value);
        }
        if (value instanceof java.sql.Date date) {
            return JsonSafeRawValue.safe(date.toLocalDate().toString());
        }
        if (value instanceof Time time) {
            return JsonSafeRawValue.safe(time.toLocalTime().toString());
        }
        if (value instanceof Timestamp timestamp) {
            return JsonSafeRawValue.safe(timestamp.toInstant().toString());
        }
        if (value instanceof Date date) {
            return JsonSafeRawValue.safe(date.toInstant().toString());
        }
        if (value instanceof Instant
                || value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof OffsetDateTime
                || value instanceof OffsetTime
                || value instanceof ZonedDateTime
                || value instanceof Year
                || value instanceof YearMonth
                || value instanceof MonthDay) {
            return JsonSafeRawValue.safe(value.toString());
        }
        if (value instanceof Map<?, ?> map) {
            if (visiting.containsKey(value)) {
                return JsonSafeRawValue.unsafe();
            }
            visiting.put(value, Boolean.TRUE);
            Map<String, Object> normalized = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    visiting.remove(value);
                    return JsonSafeRawValue.unsafe();
                }
                JsonSafeRawValue entryValue = jsonSafeValue(entry.getValue(), visiting);
                if (!entryValue.safe) {
                    visiting.remove(value);
                    return JsonSafeRawValue.unsafe();
                }
                normalized.put(key, entryValue.value);
            }
            visiting.remove(value);
            return JsonSafeRawValue.safe(normalized);
        }
        if (value instanceof List<?> list) {
            if (visiting.containsKey(value)) {
                return JsonSafeRawValue.unsafe();
            }
            visiting.put(value, Boolean.TRUE);
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                JsonSafeRawValue itemValue = jsonSafeValue(item, visiting);
                if (!itemValue.safe) {
                    visiting.remove(value);
                    return JsonSafeRawValue.unsafe();
                }
                normalized.add(itemValue.value);
            }
            visiting.remove(value);
            return JsonSafeRawValue.safe(normalized);
        }
        if (value instanceof TemporalAccessor temporalAccessor) {
            return JsonSafeRawValue.safe(temporalAccessor.toString());
        }
        return JsonSafeRawValue.unsafe();
    }

    private static String rawValueUnavailableReason(ResultCell cell) {
        if (cell.isLargeValue()) {
            return "LARGE_VALUE";
        }
        if (cell.isTruncated()) {
            return "TRUNCATED_VALUE";
        }
        Object rawValue = cell.getRawValue();
        if (rawValue != null) {
            return "UNSAFE_RAW_VALUE:" + rawValue.getClass().getName();
        }
        return "RAW_VALUE_NULL";
    }

    private record JsonSafeRawValue(boolean safe, Object value) {
        private static JsonSafeRawValue safe(Object value) {
            return new JsonSafeRawValue(true, value);
        }

        private static JsonSafeRawValue unsafe() {
            return new JsonSafeRawValue(false, null);
        }
    }

    private static boolean isSqlNull(ResultCell cell) {
        return cell.getValue() == null
                && !cell.isLargeValue()
                && !cell.isTruncated()
                && cell.getSizeBytes() == null
                && cell.getSizeChars() == null
                && cell.getLoadedBytes() == null
                && cell.getLoadedChars() == null
                && StringUtils.isBlank(cell.getLargeValueId())
                && StringUtils.isBlank(cell.getUnsupportedReason());
    }

    private static String formatExecuteResponse(ExecuteResponse result) {
        if (Objects.isNull(result)) {
            return "Empty result.";
        }
        StringBuilder builder = new StringBuilder(1024);
        builder.append("success: ").append(Boolean.TRUE.equals(result.getSuccess())).append("\n");
        if (StringUtils.isNotBlank(result.getSqlType())) {
            builder.append("sqlType: ").append(result.getSqlType()).append("\n");
        }
        if (Objects.nonNull(result.getDuration())) {
            builder.append("durationMs: ").append(result.getDuration()).append("\n");
        }
        if (Objects.nonNull(result.getUpdateCount())) {
            builder.append("updateCount: ").append(result.getUpdateCount()).append("\n");
        }
        if (StringUtils.isNotBlank(result.getMessage())) {
            builder.append("message: ").append(result.getMessage()).append("\n");
        }
        if (StringUtils.isNotBlank(result.getDescription())) {
            builder.append("description: ").append(result.getDescription()).append("\n");
        }
        if (CollectionUtils.isNotEmpty(result.getHeaderList()) && CollectionUtils.isNotEmpty(result.getDataList())) {
            builder.append("rows: ").append(result.getDataList().size());
            if (Objects.nonNull(result.getHasNextPage())) {
                builder.append(", hasNextPage: ").append(result.getHasNextPage());
            }
            builder.append("\n");
            appendTabularPreview(builder, result.getHeaderList(), result.getDisplayDataList());
        }
        return builder.toString().trim();
    }

    private static void appendTabularPreview(StringBuilder builder, List<Header> headers, List<List<String>> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return;
        }
        List<String> headerNames = columnNames(headers);
        builder.append(String.join("\t", headerNames)).append("\n");
        int rowCount = Math.min(rows.size(), AI_SQL_PREVIEW_ROW_LIMIT);
        for (int i = 0; i < rowCount; i++) {
            List<String> row = rows.get(i);
            List<String> normalized = new ArrayList<>(headerNames.size());
            for (int c = 0; c < headerNames.size(); c++) {
                String value = row != null && c < row.size() ? row.get(c) : null;
                normalized.add(normalizeCell(value));
            }
            builder.append(String.join("\t", normalized)).append("\n");
        }
        if (rows.size() > rowCount) {
            builder.append("... ").append(rows.size() - rowCount).append(" more rows not shown.");
        }
    }

    private static String normalizeCell(String value) {
        if (value == null) {
            return "NULL";
        }
        String normalized = value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", " ");
        if (normalized.length() > 200) {
            return normalized.substring(0, 197) + "...";
        }
        return normalized;
    }

    private String buildRichTableSchema(String tableName, String ddl, Table table) {
        StringBuilder builder = new StringBuilder(2048);
        builder.append("-- TABLE: ").append(tableName).append("\n");
        builder.append("/* physical schema */\n");
        builder.append(StringUtils.defaultIfBlank(ddl, "-- schema unavailable"));

        String primaryKeys = formatPrimaryKeys(table);
        if (StringUtils.isNotBlank(primaryKeys)) {
            builder.append("\n\n").append(primaryKeys);
        }

        String indexes = formatIndexes(table);
        if (StringUtils.isNotBlank(indexes)) {
            builder.append("\n\n").append(indexes);
        }

        String foreignKeys = formatForeignKeys(table);
        if (StringUtils.isNotBlank(foreignKeys)) {
            builder.append("\n\n").append(foreignKeys);
        }

        return builder.toString();
    }

    private String formatPrimaryKeys(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getColumnList())) {
            return null;
        }
        List<TableColumn> primaryKeys = table.getColumnList().stream()
                .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
                .sorted(Comparator.comparingInt(column -> Objects.requireNonNullElse(column.getPrimaryKeyOrder(), 0)))
                .toList();
        if (CollectionUtils.isEmpty(primaryKeys)) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("/* primary keys */");
        lines.add(primaryKeys.stream()
                .map(TableColumn::getName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(", ")));
        return String.join("\n", lines);
    }

    private String formatIndexes(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getIndexList())) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("/* indexes */");
        for (TableIndex index : table.getIndexList()) {
            List<TableIndexColumn> columns = index.getColumnList();
            String columnNames = CollectionUtils.isEmpty(columns)
                    ? ""
                    : columns.stream()
                    .sorted(Comparator.comparing(column -> Objects.requireNonNullElse(column.getOrdinalPosition(), (short) 0)))
                    .map(TableIndexColumn::getColumnName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            List<String> parts = new ArrayList<>();
            parts.add("type=" + StringUtils.defaultIfBlank(index.getType(), "INDEX"));
            parts.add("unique=" + Boolean.TRUE.equals(index.getUnique()));
            if (StringUtils.isNotBlank(index.getMethod())) {
                parts.add("method=" + index.getMethod());
            }
            if (StringUtils.isNotBlank(index.getComment())) {
                parts.add("comment=" + index.getComment());
            }
            lines.add("- " + StringUtils.defaultIfBlank(index.getName(), "(unnamed)")
                    + (StringUtils.isNotBlank(columnNames) ? " (" + columnNames + ")" : "")
                    + " | " + String.join("; ", parts));
        }
        return lines.size() > 1 ? String.join("\n", lines) : null;
    }

    private String formatForeignKeys(Table table) {
        if (table == null || CollectionUtils.isEmpty(table.getForeignKeyList())) {
            return null;
        }
        Map<String, List<ForeignKeyInfo>> grouped = new LinkedHashMap<>();
        for (ForeignKeyInfo foreignKey : table.getForeignKeyList()) {
            String key = firstNonBlank(foreignKey.getFkName(),
                    foreignKey.getFkTableName() + "->" + foreignKey.getPkTableName());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(foreignKey);
        }

        List<String> lines = new ArrayList<>();
        lines.add("/* foreign keys */");
        for (Map.Entry<String, List<ForeignKeyInfo>> entry : grouped.entrySet()) {
            List<ForeignKeyInfo> fkList = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ForeignKeyInfo::getKeySeq))
                    .toList();
            String fkColumns = fkList.stream()
                    .map(ForeignKeyInfo::getFkColumnName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            String pkTable = fkList.stream()
                    .map(ForeignKeyInfo::getPkTableName)
                    .filter(StringUtils::isNotBlank)
                    .findFirst()
                    .orElse("(unknown)");
            String pkColumns = fkList.stream()
                    .map(ForeignKeyInfo::getPkColumnName)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining(", "));
            lines.add("- " + entry.getKey() + ": (" + fkColumns + ") -> " + pkTable + "(" + pkColumns + ")");
        }
        return lines.size() > 1 ? String.join("\n", lines) : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private <T> List<T> emptyIfNull(List<T> items) {
        return items == null ? Collections.emptyList() : items;
    }
}
