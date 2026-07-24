package ai.chat2db.plugin.mysql.completion.hint;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatusEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionMetadataScope;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionMetadataRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import ai.chat2db.community.domain.api.service.db.ISqlCompletionMetadataProvider;
import ai.chat2db.plugin.mysql.completion.util.MysqlSqlCompletionTokenUtil;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

final class MysqlSqlCompletionColumnMetadata {

    private MysqlSqlCompletionColumnMetadata() {
    }

    static Map<String, SqlCompletionCandidate> load(ISqlCompletionMetadataProvider metadataProvider,
                                                    String catalog,
                                                    String schema,
                                                    String table) {
        if (metadataProvider == null || StringUtils.isBlank(table)) {
            return Map.of();
        }
        SqlCompletionMetadataScope scope = new SqlCompletionMetadataScope(catalog, schema, table, null);
        SqlCompletionMetadataResponse result = metadataProvider.list(DbSqlCompletionMetadataRequest.of(
                SqlCompletionCandidateTypeEnum.COLUMN, scope, ""));
        if (result == null || !SqlCompletionStatusEnum.SUCCESS.name().equals(result.getStatus())
                || result.getCandidates() == null) {
            return Map.of();
        }
        return result.getCandidates().stream()
                .filter(Objects::nonNull)
                .filter(candidate -> normalize(columnName(candidate)) != null)
                .sorted(Comparator.comparingInt(candidate -> candidate.getSortRank() == null
                        ? Integer.MAX_VALUE
                        : candidate.getSortRank()))
                .collect(Collectors.toMap(candidate -> normalize(columnName(candidate)), Function.identity(),
                        (left, right) -> left));
    }

    static SqlCompletionCandidate find(Map<String, SqlCompletionCandidate> columns, String columnName) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        return columns.get(normalize(columnName));
    }

    static String columnName(SqlCompletionCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(candidate.getColumnName(), candidate.getLabel());
    }

    static String columnType(SqlCompletionCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(candidate.getDataType(), candidate.getDetail());
    }

    static String normalize(String name) {
        String stripped = MysqlSqlCompletionTokenUtil.stripIdentifierQuotes(name);
        return StringUtils.isBlank(stripped) ? null : stripped.toLowerCase(Locale.ROOT);
    }
}
