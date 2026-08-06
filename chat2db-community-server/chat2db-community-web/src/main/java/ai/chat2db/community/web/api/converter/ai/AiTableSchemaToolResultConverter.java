package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.web.api.model.response.ai.AiTableSchemaToolPayload;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class AiTableSchemaToolResultConverter {

    public AiToolOutput<List<AiTableSchemaToolPayload>> fromTableSchemas(List<TableSchemaResult> schemaResults) {
        List<AiTableSchemaToolPayload> items = emptyIfNull(schemaResults).stream()
                .filter(Objects::nonNull)
                .map(result -> new AiTableSchemaToolPayload(
                        result.getTableName(),
                        buildRichTableSchema(result.getTableName(), result.getDdl(), result.getTable())))
                .collect(Collectors.toList());
        String summary = items.isEmpty()
                ? "No table schema found."
                : "Fetched schema for " + items.size() + " table(s).";
        return new AiToolOutput<>(summary, items);
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
