package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.agent.AgentDataScope;
import ai.chat2db.community.tools.util.EasySqlUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AgentToolScopePolicy {

    private AgentToolScopePolicy() {
    }

    public static void requireConnection(AgentDataScope scope, Long dataSourceId, String databaseName, String schemaName) {
        if (scope == null) {
            return;
        }
        if (!Objects.equals(scope.getDataSourceId(), dataSourceId)
                || !containsScopeName(scope.getDatabaseName(), databaseName)
                || !containsScopeName(scope.getSchemaName(), schemaName)) {
            throw new IllegalArgumentException("database target is outside the Agent Task data scope");
        }
    }

    public static boolean allowsTable(AgentDataScope scope, String tableName) {
        if (scope == null) {
            return true;
        }
        if (StringUtils.isBlank(tableName)) {
            return false;
        }
        String normalized = normalizeTableName(tableName);
        if (normalizedNames(scope.getExcludedTableNames()).contains(normalized)) {
            return false;
        }
        Set<String> allowed = normalizedNames(scope.getTableNames());
        return allowed.isEmpty() || allowed.contains(normalized);
    }

    public static void requireSql(AgentDataScope scope, String sql) {
        if (scope == null) {
            return;
        }
        StringBuilder parseError = new StringBuilder();
        Map<String, Object> schemaInfo = EasySqlUtils.extractTableSchemaInfo(sql, parseError);
        if (parseError.length() > 0) {
            throw new IllegalArgumentException("SQL cannot be safely checked against the Agent Task data scope");
        }
        for (String database : stringValues(schemaInfo.get(EasySqlUtils.DATABASE_NAME))) {
            if (!containsScopeName(scope.getDatabaseName(), database)) {
                throw new IllegalArgumentException("SQL database is outside the Agent Task data scope");
            }
        }
        for (String schema : stringValues(schemaInfo.get(EasySqlUtils.SCHEMA_NAME))) {
            if (!containsScopeName(scope.getSchemaName(), schema)) {
                throw new IllegalArgumentException("SQL schema is outside the Agent Task data scope");
            }
        }
        if (stringValues(schemaInfo.get(EasySqlUtils.TABLE_NAME)).stream()
                .anyMatch(table -> !allowsTable(scope, table))) {
            throw new IllegalArgumentException("SQL table is outside the Agent Task data scope");
        }
    }

    public static int capRows(AgentDataScope scope, Integer requestedRows, int defaultRows, int maximumRows) {
        int requested = requestedRows == null || requestedRows <= 0 ? defaultRows : requestedRows;
        int scopeLimit = scope == null || scope.getMaxRows() == null || scope.getMaxRows() <= 0
                ? maximumRows
                : scope.getMaxRows();
        return Math.min(Math.min(requested, maximumRows), scopeLimit);
    }

    private static boolean containsScopeName(String granted, String requested) {
        return StringUtils.isBlank(granted)
                || (StringUtils.isNotBlank(requested) && granted.equalsIgnoreCase(requested));
    }

    private static List<String> stringValues(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static Set<String> normalizedNames(List<String> names) {
        Set<String> normalized = new HashSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (StringUtils.isNotBlank(name)) {
                normalized.add(normalizeTableName(name));
            }
        }
        return normalized;
    }

    private static String normalizeTableName(String tableName) {
        String normalized = tableName.trim();
        int separator = normalized.lastIndexOf('.');
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        return normalized.replaceAll("^[`\"\\[]+|[`\"\\]]+$", "").toLowerCase(Locale.ROOT);
    }
}
