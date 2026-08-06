package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.exception.ai.AiToolSqlExecutionException;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.web.api.model.response.ai.AiSqlCellMetadataEntryPayload;
import ai.chat2db.community.web.api.model.response.ai.AiSqlResultSetPayload;
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
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AiSqlToolResultConverter {

    // AI preview limit only affects the serialized tool payload; it is not a database execution limit.
    private static final int AI_SQL_PREVIEW_ROW_LIMIT = 50;

    public AiToolOutput<List<AiSqlResultSetPayload>> fromExecuteResult(List<ExecuteResponse> executeResponses) {
        List<AiSqlResultSetPayload> items = new ArrayList<>();
        int index = 1;
        for (ExecuteResponse response : emptyIfNull(executeResponses)) {
            if (response == null) {
                throw new AiToolSqlExecutionException("SQL execution returned an empty result.");
            }
            items.add(executeResponseData(index++, response));
        }
        String summary = items.isEmpty()
                ? "SQL executed successfully with no result."
                : "SQL executed successfully with " + items.size() + " result set(s).";
        return new AiToolOutput<>(summary, items);
    }

    static AiSqlResultSetPayload executeResponseData(int index, ExecuteResponse result) {
        if (Objects.isNull(result)) {
            throw new AiToolSqlExecutionException("SQL execution returned an empty result.");
        }
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new AiToolSqlExecutionException("SQL execution returned a failed result.");
        }
        AiSqlResultSetPayload item = new AiSqlResultSetPayload();
        item.setResultIndex(index);
        item.setSqlType(result.getSqlType());
        item.setDurationMs(result.getDuration());
        item.setUpdateCount(result.getUpdateCount());
        item.setHasNextPage(result.getHasNextPage());
        int rowCount = result.getDataList() == null ? 0 : result.getDataList().size();
        int previewRowCount = Math.min(rowCount, AI_SQL_PREVIEW_ROW_LIMIT);
        item.setRowCount(rowCount);
        item.setPreviewRowCount(previewRowCount);
        item.setRowsTruncated(rowCount > previewRowCount);
        item.setColumns(columnNames(result.getHeaderList()));
        item.setRows(rowPreviewRows(result.getHeaderList(), result.getDataList()));
        item.setCellMetadata(cellMetadataEntries(result.getHeaderList(), result.getDataList()));
        return item;
    }

    static List<String> columnNames(List<Header> headers) {
        if (CollectionUtils.isEmpty(headers)) {
            return Collections.emptyList();
        }
        return headers.stream()
                .map(header -> StringUtils.defaultIfBlank(header.getName(), header.getColumnName()))
                .map(name -> StringUtils.defaultIfBlank(name, "col"))
                .toList();
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

    static List<AiSqlCellMetadataEntryPayload> cellMetadataEntries(List<Header> headers, List<List<ResultCell>> rows) {
        if (CollectionUtils.isEmpty(headers) || CollectionUtils.isEmpty(rows)) {
            return Collections.emptyList();
        }
        List<String> headerNames = columnNames(headers);
        int rowCount = Math.min(rows.size(), AI_SQL_PREVIEW_ROW_LIMIT);
        List<AiSqlCellMetadataEntryPayload> result = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            List<ResultCell> row = rows.get(i);
            for (int c = 0; c < headerNames.size(); c++) {
                ResultCell cell = row != null && c < row.size() ? row.get(c) : null;
                AiSqlCellMetadataEntryPayload metadata = cellMetadataEntry(i, c, cell);
                if (metadata != null) {
                    result.add(metadata);
                }
            }
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

    private static AiSqlCellMetadataEntryPayload cellMetadataEntry(int rowIndex, int columnIndex, ResultCell cell) {
        if (cell == null || jsonSafeRawValue(cell).safe || isSqlNull(cell)) {
            return null;
        }
        return new AiSqlCellMetadataEntryPayload(
                rowIndex,
                columnIndex,
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

    private static <T> List<T> emptyIfNull(List<T> items) {
        return items == null ? Collections.emptyList() : items;
    }

    private record JsonSafeRawValue(boolean safe, Object value) {
        private static JsonSafeRawValue safe(Object value) {
            return new JsonSafeRawValue(true, value);
        }

        private static JsonSafeRawValue unsafe() {
            return new JsonSafeRawValue(false, null);
        }
    }
}
