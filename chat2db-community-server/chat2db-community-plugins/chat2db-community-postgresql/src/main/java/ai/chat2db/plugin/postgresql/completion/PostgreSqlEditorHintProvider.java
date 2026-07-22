package ai.chat2db.plugin.postgresql.completion;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionEditorHintTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatusEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionMetadataScope;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionMetadataRequest;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/** PostgreSQL-specific non-executable hints for INSERT values. */
public final class PostgreSqlEditorHintProvider {

    private static final String IDENTIFIER = "(?:\"(?:[^\"]|\"\")*\"|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final Pattern INSERT_VALUES = Pattern.compile(
            "(?is)\\binsert\\s+into\\s+(" + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER
                    + ")?)\\s*\\(([^()]*)\\)\\s*values\\s*\\((.*)$");

    public List<SqlCompletionEditorHint> build(DbSqlCompletionRequest request) {
        if (request == null || request.metadataProvider() == null) {
            return List.of();
        }
        String sql = Objects.toString(request.sql(), "");
        int cursor = Math.max(0, Math.min(request.cursor(), sql.length()));
        String beforeCursor = sql.substring(0, cursor);
        if (insideLiteralOrComment(beforeCursor)) {
            return List.of();
        }
        SqlCompletionEditorHint insertHint = buildInsertHint(request, sql, cursor, beforeCursor);
        return insertHint == null ? List.of() : List.of(insertHint);
    }

    private SqlCompletionEditorHint buildInsertHint(DbSqlCompletionRequest request, String sql, int cursor,
                                                    String beforeCursor) {
        Matcher matcher = INSERT_VALUES.matcher(beforeCursor);
        if (!matcher.find()) {
            return null;
        }
        Relation relation = relation(matcher.group(1));
        List<String> columns = splitIdentifiers(matcher.group(2));
        if (relation == null || columns.isEmpty()) {
            return null;
        }
        List<ValueRange> values = valueRanges(matcher.group(3), matcher.start(3));
        Map<String, SqlCompletionCandidate> metadata = loadColumns(request, relation);
        if (metadata.isEmpty()) {
            return null;
        }
        List<SqlCompletionEditorHint.Item> items = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            String fieldName = columns.get(index);
            SqlCompletionCandidate column = metadata.get(normalize(fieldName));
            if (column == null) {
                return null;
            }
            ValueRange valueRange = index < values.size() ? values.get(index) : new ValueRange(cursor, cursor);
            boolean active = values.isEmpty() ? index == 0 : index == values.size() - 1;
            items.add(item(sql, valueRange.start(), valueRange.end(), 0, index,
                    fieldName, columnType(column), active));
        }
        int activeValueStart = values.isEmpty() ? cursor : values.get(values.size() - 1).start();
        SqlCompletionEditorHint hint = hint(sql, matcher.start(), cursor, activeValueStart,
                SqlCompletionEditorHintTypeEnum.INSERT_VALUE, items);
        hint.setRowRange(SqlCompletionEditorHint.Range.ofOffsets(sql, matcher.start(3), cursor));
        return hint;
    }

    private SqlCompletionEditorHint hint(String sql, int statementStart, int cursor, int valueStart,
                                         SqlCompletionEditorHintTypeEnum type,
                                         List<SqlCompletionEditorHint.Item> items) {
        SqlCompletionEditorHint hint = new SqlCompletionEditorHint();
        hint.setType(type);
        hint.setStatementRange(SqlCompletionEditorHint.Range.ofOffsets(sql, statementStart, cursor));
        hint.setValueRange(SqlCompletionEditorHint.Range.ofOffsets(sql, valueStart, cursor));
        hint.setItems(items);
        return hint;
    }

    private SqlCompletionEditorHint.Item item(String sql, int startOffset, int endOffset,
                                              int rowIndex, int columnIndex,
                                              String fieldName, String fieldType, boolean active) {
        SqlCompletionEditorHint.Item item = new SqlCompletionEditorHint.Item();
        item.setRowIndex(rowIndex);
        item.setColumnIndex(columnIndex);
        item.setFieldName(fieldName);
        item.setFieldType(fieldType);
        item.setDefaultValue(defaultValue(fieldType));
        item.setLabel(StringUtils.isBlank(fieldType) ? fieldName : fieldName + ":" + fieldType);
        item.setRange(SqlCompletionEditorHint.Range.ofOffsets(sql, startOffset, endOffset));
        item.setActive(active);
        return item;
    }

    private Map<String, SqlCompletionCandidate> loadColumns(DbSqlCompletionRequest request, Relation relation) {
        if (relation == null) {
            return Map.of();
        }
        SqlCompletionMetadataResponse response = request.metadataProvider().list(
                DbSqlCompletionMetadataRequest.of(SqlCompletionCandidateTypeEnum.COLUMN,
                        new SqlCompletionMetadataScope(null, relation.schema(), relation.table(), null), ""));
        if (response == null || !SqlCompletionStatusEnum.SUCCESS.name().equals(response.getStatus())
                || response.getCandidates() == null) {
            return Map.of();
        }
        Map<String, SqlCompletionCandidate> result = new LinkedHashMap<>();
        response.getCandidates().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(candidate -> candidate.getSortRank() == null
                        ? Integer.MAX_VALUE : candidate.getSortRank()))
                .forEach(candidate -> {
                    String name = StringUtils.defaultIfBlank(candidate.getColumnName(), candidate.getLabel());
                    if (StringUtils.isNotBlank(name)) {
                        result.putIfAbsent(normalize(name), candidate);
                    }
                });
        return result;
    }

    private Relation relation(String value) {
        List<String> parts = splitQualifiedIdentifier(value);
        if (parts.size() == 1) {
            return new Relation(null, parts.get(0));
        }
        if (parts.size() == 2) {
            return new Relation(parts.get(0), parts.get(1));
        }
        return null;
    }

    private List<String> splitQualifiedIdentifier(String value) {
        return split(value, '.');
    }

    private List<String> splitIdentifiers(String value) {
        return split(value, ',');
    }

    private List<String> split(String value, char separator) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < Objects.toString(value, "").length(); index++) {
            char currentChar = value.charAt(index);
            if (currentChar == '"') {
                current.append(currentChar);
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '"') {
                    current.append(value.charAt(++index));
                } else {
                    quoted = !quoted;
                }
            } else if (currentChar == separator && !quoted) {
                addIdentifier(result, current);
            } else {
                current.append(currentChar);
            }
        }
        addIdentifier(result, current);
        return result;
    }

    private List<ValueRange> valueRanges(String value, int sourceOffset) {
        List<ValueRange> result = new ArrayList<>();
        int segmentStart = 0;
        int depth = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char next = index + 1 < value.length() ? value.charAt(index + 1) : '\0';
            if (!doubleQuoted && current == '\'' && !(singleQuoted && next == '\'')) {
                singleQuoted = !singleQuoted;
            } else if (singleQuoted && current == '\'' && next == '\'') {
                index++;
            } else if (!singleQuoted && current == '"' && !(doubleQuoted && next == '"')) {
                doubleQuoted = !doubleQuoted;
            } else if (doubleQuoted && current == '"' && next == '"') {
                index++;
            } else if (!singleQuoted && !doubleQuoted) {
                if (current == '(' || current == '[') {
                    depth++;
                } else if (current == ')' || current == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (current == ',' && depth == 0) {
                    addValueRange(result, value, sourceOffset, segmentStart, index);
                    segmentStart = index + 1;
                }
            }
        }
        addValueRange(result, value, sourceOffset, segmentStart, value.length());
        return result;
    }

    private void addValueRange(List<ValueRange> result, String value, int sourceOffset, int start, int end) {
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            result.add(new ValueRange(sourceOffset + start, sourceOffset + end));
        }
    }

    private void addIdentifier(List<String> result, StringBuilder value) {
        String identifier = stripQuotes(value.toString().trim());
        if (StringUtils.isNotBlank(identifier)) {
            result.add(identifier);
        }
        value.setLength(0);
    }

    private String stripQuotes(String value) {
        String trimmed = StringUtils.trimToEmpty(value);
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    private String normalize(String value) {
        return stripQuotes(value).toLowerCase(Locale.ROOT);
    }

    private String columnType(SqlCompletionCandidate candidate) {
        return StringUtils.defaultIfBlank(candidate.getDataType(), candidate.getDetail());
    }

    private String defaultValue(String type) {
        String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(type), Locale.ROOT);
        if (normalized.endsWith("[]")) {
            return "'{}'";
        }
        if (normalized.contains("bool")) {
            return "FALSE";
        }
        if (normalized.matches(".*\\b(smallint|integer|bigint|int2|int4|int8|smallserial|serial|bigserial)\\b.*")) {
            return "0";
        }
        if (normalized.matches(".*\\b(numeric|decimal|real|double precision|float4|float8|money)\\b.*")) {
            return "0.0";
        }
        if (normalized.startsWith("timestamp")) {
            return "CURRENT_TIMESTAMP";
        }
        if (normalized.startsWith("date")) {
            return "CURRENT_DATE";
        }
        if (normalized.startsWith("time")) {
            return "CURRENT_TIME";
        }
        if (normalized.equals("jsonb")) {
            return "'{}'::jsonb";
        }
        if (normalized.equals("json")) {
            return "'{}'::json";
        }
        if (normalized.equals("bytea")) {
            return "'\\\\x'::bytea";
        }
        if (normalized.matches(".*\\b(character varying|varchar|character|char|text|citext|name)\\b.*")) {
            return "''";
        }
        return "NULL";
    }

    private boolean insideLiteralOrComment(String sql) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (!singleQuoted && !doubleQuoted && current == '-' && next == '-') {
                int newline = sql.indexOf('\n', index + 2);
                if (newline < 0) {
                    return true;
                }
                index = newline;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && current == '/' && next == '*') {
                int close = sql.indexOf("*/", index + 2);
                if (close < 0) {
                    return true;
                }
                index = close + 1;
                continue;
            }
            if (!doubleQuoted && current == '\'' && !(singleQuoted && next == '\'')) {
                singleQuoted = !singleQuoted;
            } else if (singleQuoted && current == '\'' && next == '\'') {
                index++;
            } else if (!singleQuoted && current == '"' && !(doubleQuoted && next == '"')) {
                doubleQuoted = !doubleQuoted;
            } else if (doubleQuoted && current == '"' && next == '"') {
                index++;
            }
        }
        return singleQuoted;
    }

    private record Relation(String schema, String table) {
    }

    private record ValueRange(int start, int end) {
    }
}
