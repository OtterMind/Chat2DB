package ai.chat2db.spi.completion;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * Conservative INSERT value hints shared by relational dialects that use the
 * standard {@code INSERT INTO table (columns) VALUES (...)} shape.
 */
public final class StandardSqlInsertEditorHintProvider {

    private static final String IDENTIFIER = "(?:\"(?:[^\"]|\"\")*\"|`(?:[^`]|``)*`|\\[(?:[^\\]]|\\]\\])*\\]|[A-Za-z_][A-Za-z0-9_$]*)";
    private static final Pattern INSERT_VALUES = Pattern.compile(
            "(?is)\\binsert\\s+into\\s+(" + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER
                    + "){0,2})\\s*\\(([^()]*)\\)\\s*values\\s*\\((.*)$");
    private static final Set<String> MYSQL_FAMILY = Set.of("MARIADB", "TIDB", "OCEANBASE");
    private static final Set<String> ZERO_BOOLEAN_DIALECTS = Set.of(
            "SQLSERVER", "ORACLE", "OCEANBASE_ORACLE", "DB2", "DM", "OSCAR", "XUGUDB", "GBASE8S",
            "INFORMIX", "INFOMIX");

    private final String databaseType;

    public StandardSqlInsertEditorHintProvider(String databaseType) {
        this.databaseType = StringUtils.upperCase(StringUtils.trimToEmpty(databaseType), Locale.ROOT);
    }

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
        Matcher matcher = INSERT_VALUES.matcher(beforeCursor);
        if (!matcher.find()) {
            return List.of();
        }
        Relation relation = relation(matcher.group(1));
        List<String> columns = splitIdentifiers(matcher.group(2));
        if (relation == null || columns.isEmpty()) {
            return List.of();
        }
        Map<String, SqlCompletionCandidate> metadata = loadColumns(request, relation);
        if (metadata.isEmpty()) {
            return List.of();
        }
        List<ValueRange> values = valueRanges(matcher.group(3), matcher.start(3));
        List<SqlCompletionEditorHint.Item> items = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            String fieldName = columns.get(index);
            SqlCompletionCandidate column = metadata.get(normalize(fieldName));
            if (column == null) {
                return List.of();
            }
            ValueRange range = index < values.size() ? values.get(index) : new ValueRange(cursor, cursor);
            boolean active = values.isEmpty() ? index == 0 : index == values.size() - 1;
            items.add(item(sql, range, index, fieldName, columnType(column), active));
        }
        int activeValueStart = values.isEmpty() ? cursor : values.get(values.size() - 1).start();
        SqlCompletionEditorHint hint = new SqlCompletionEditorHint();
        hint.setType(SqlCompletionEditorHintTypeEnum.INSERT_VALUE);
        hint.setStatementRange(SqlCompletionEditorHint.Range.ofOffsets(sql, matcher.start(), cursor));
        hint.setRowRange(SqlCompletionEditorHint.Range.ofOffsets(sql, matcher.start(3), cursor));
        hint.setValueRange(SqlCompletionEditorHint.Range.ofOffsets(sql, activeValueStart, cursor));
        hint.setItems(items);
        return List.of(hint);
    }

    private SqlCompletionEditorHint.Item item(String sql, ValueRange range, int columnIndex,
                                              String fieldName, String fieldType, boolean active) {
        SqlCompletionEditorHint.Item item = new SqlCompletionEditorHint.Item();
        item.setRowIndex(0);
        item.setColumnIndex(columnIndex);
        item.setFieldName(fieldName);
        item.setFieldType(fieldType);
        item.setDefaultValue(defaultValue(fieldType));
        item.setLabel(StringUtils.isBlank(fieldType) ? fieldName : fieldName + ":" + fieldType);
        item.setRange(SqlCompletionEditorHint.Range.ofOffsets(sql, range.start(), range.end()));
        item.setActive(active);
        return item;
    }

    private Map<String, SqlCompletionCandidate> loadColumns(DbSqlCompletionRequest request, Relation relation) {
        SqlCompletionMetadataResponse response = request.metadataProvider().list(
                DbSqlCompletionMetadataRequest.of(SqlCompletionCandidateTypeEnum.COLUMN,
                        new SqlCompletionMetadataScope(relation.catalog(), relation.schema(), relation.table(), null), ""));
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
        List<String> parts = split(value, '.');
        if (parts.size() == 1) {
            return new Relation(null, null, parts.get(0));
        }
        if (parts.size() == 2) {
            return MYSQL_FAMILY.contains(databaseType)
                    ? new Relation(parts.get(0), null, parts.get(1))
                    : new Relation(null, parts.get(0), parts.get(1));
        }
        if (parts.size() == 3) {
            return new Relation(parts.get(0), parts.get(1), parts.get(2));
        }
        return null;
    }

    private List<String> splitIdentifiers(String value) {
        return split(value, ',');
    }

    private List<String> split(String value, char separator) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quoteEnd = '\0';
        for (int index = 0; index < Objects.toString(value, "").length(); index++) {
            char currentChar = value.charAt(index);
            if (quoteEnd == '\0' && (currentChar == '\"' || currentChar == '`' || currentChar == '[')) {
                quoteEnd = currentChar == '[' ? ']' : currentChar;
                current.append(currentChar);
            } else if (quoteEnd != '\0' && currentChar == quoteEnd) {
                current.append(currentChar);
                if (index + 1 < value.length() && value.charAt(index + 1) == quoteEnd) {
                    current.append(value.charAt(++index));
                } else {
                    quoteEnd = '\0';
                }
            } else if (currentChar == separator && quoteEnd == '\0') {
                addIdentifier(result, current);
            } else {
                current.append(currentChar);
            }
        }
        addIdentifier(result, current);
        return result;
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
        if (trimmed.length() < 2) {
            return trimmed;
        }
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        if ((first == '\"' && last == '\"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
            String body = trimmed.substring(1, trimmed.length() - 1);
            String escaped = first == '[' ? "]]" : String.valueOf(first) + first;
            String replacement = first == '[' ? "]" : String.valueOf(first);
            return body.replace(escaped, replacement);
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
        if (normalized.contains("bool") || normalized.equals("bit")) {
            return ZERO_BOOLEAN_DIALECTS.contains(databaseType) ? "0" : "FALSE";
        }
        if (normalized.matches(".*\\bnumber\\s*\\(\\s*\\d+\\s*(?:,\\s*0\\s*)?\\).*")
                || normalized.matches(".*\\b(tinyint|smallint|integer|bigint|int|int2|int4|int8)\\b.*")) {
            return "0";
        }
        if (normalized.matches(".*\\b(numeric|decimal|number|real|double|float|money|decfloat)\\b.*")) {
            return "0.0";
        }
        if (normalized.contains("timestamp") || normalized.contains("datetime")) {
            return "CURRENT_TIMESTAMP";
        }
        if (normalized.matches(".*\\bdate\\b.*")) {
            if ("SQLSERVER".equals(databaseType)) {
                return "CAST(CURRENT_TIMESTAMP AS DATE)";
            }
            return "BIGQUERY".equals(databaseType) ? "CURRENT_DATE()" : "CURRENT_DATE";
        }
        if (normalized.matches(".*\\btime\\b.*")) {
            if ("SQLSERVER".equals(databaseType)) {
                return "CAST(CURRENT_TIMESTAMP AS TIME)";
            }
            return "BIGQUERY".equals(databaseType) ? "CURRENT_TIME()" : "CURRENT_TIME";
        }
        if (normalized.matches(".*\\b(char|varchar|varchar2|nvarchar|nvarchar2|text|clob|string|enum|set)\\b.*")) {
            return "''";
        }
        return "NULL";
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
            } else if (!singleQuoted && current == '\"' && !(doubleQuoted && next == '\"')) {
                doubleQuoted = !doubleQuoted;
            } else if (doubleQuoted && current == '\"' && next == '\"') {
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
            } else if (!singleQuoted && current == '\"' && !(doubleQuoted && next == '\"')) {
                doubleQuoted = !doubleQuoted;
            } else if (doubleQuoted && current == '\"' && next == '\"') {
                index++;
            }
        }
        return singleQuoted;
    }

    private record Relation(String catalog, String schema, String table) {
    }

    private record ValueRange(int start, int end) {
    }
}
