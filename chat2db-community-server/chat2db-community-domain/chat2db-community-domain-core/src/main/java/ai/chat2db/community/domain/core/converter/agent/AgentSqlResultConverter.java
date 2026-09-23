package ai.chat2db.community.domain.core.converter.agent;

import ai.chat2db.community.domain.api.model.request.agent.DbAgentDatabaseRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.*;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.tools.exception.agent.AgentDatabaseException;
import ai.chat2db.community.tools.model.agent.tool.AgentToolNextAction;
import java.util.*;

public final class AgentSqlResultConverter {
    private AgentSqlResultConverter() { }

    public static DbAgentDatabaseResponse<SqlExecutionData> toResponse(DbAgentDatabaseRequest.Query request,
            ConnectionProfile profile, List<ExecuteResponse> responses, int statementCount, boolean readOnly) {
        if (responses.isEmpty()) throw new AgentDatabaseException("UNEXPECTED_RESULT", "sql", "SQL execution returned no outcome.", null);
        int page = request.page() == null ? 1 : request.page();
        int size = request.pageSize() == null ? 50 : request.pageSize();
        List<SqlResult> results = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            ExecuteResponse response = responses.get(i);
            int index = response.getStatementSequence() == null ? i + 1 : response.getStatementSequence();
            String sql = Objects.toString(response.getOriginalSql(), Objects.toString(response.getSql(), request.sql()));
            boolean success = Boolean.TRUE.equals(response.getSuccess());
            QueryData data = success ? toQueryData(response) : null;
            Page pagination = success ? new Page(page, size, data.rows().size(), null,
                    Boolean.TRUE.equals(response.getHasNextPage()),
                    Boolean.TRUE.equals(response.getHasNextPage()) ? page + 1 : null) : null;
            var error = success ? null : new DbAgentDatabaseResponse.Error("SQL_ERROR", "sql", Objects.toString(response.getMessage(), "SQL execution failed"));
            results.add(new SqlResult(index, sql, success, data, pagination, error));
            if (data != null && !data.cellWarnings().isEmpty()) warnings.add("Result " + index + " has incomplete cells; see its data.cellWarnings.");
        }
        var failure = results.stream().filter(result -> !result.success()).findFirst();
        if (failure.isPresent()) warnings.add("Execution stopped after an error. Earlier statements may already have taken effect; do not replay the batch automatically.");
        boolean hasMore = readOnly && results.stream().anyMatch(result -> result.page() != null && Boolean.TRUE.equals(result.page().hasMore()));
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("dataSourceId", request.dataSourceId());
        if (request.database() != null) next.put("database", request.database());
        if (request.schema() != null) next.put("schema", request.schema());
        next.put("sql", request.sql()); next.put("page", page + 1); next.put("pageSize", size);
        return new DbAgentDatabaseResponse<>(failure.isEmpty(),
                new Scope(String.valueOf(profile.getDataSourceId()), profile.getDbType(), profile.getDatabaseName(), profile.getSchemaName()),
                new SqlExecutionData(List.copyOf(results), statementCount, readOnly),
                results.size() == 1 ? results.get(0).page() : null,
                failure.map(SqlResult::error).orElse(null),
                hasMore && failure.isEmpty() ? new AgentToolNextAction("db_query", next) : null, List.copyOf(warnings));
    }

    private static QueryData toQueryData(ExecuteResponse response) {
        List<Header> headers = response.getHeaderList() == null ? List.of() : response.getHeaderList();
        List<Integer> indexes = java.util.stream.IntStream.range(0, headers.size())
                .filter(i -> !DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode().equals(headers.get(i).getDataType())).boxed().toList();
        List<QueryColumn> columns = indexes.stream().map(i -> {
            Header header = headers.get(i);
            return new QueryColumn(header.getName() == null ? header.getColumnName() : header.getName(),
                    header.getColumnType() == null ? header.getDataType() : header.getColumnType());
        }).toList();
        List<List<String>> rows = new ArrayList<>();
        List<CellWarning> warnings = new ArrayList<>();
        if (response.getDataList() != null) {
            for (var sourceRow : response.getDataList()) {
                if (sourceRow == null || sourceRow.size() != headers.size()) {
                    throw new AgentDatabaseException("UNEXPECTED_RESULT", null, "Result row does not match column metadata.", null);
                }
                List<String> row = new ArrayList<>();
                for (int index : indexes) {
                    var cell = sourceRow.get(index);
                    if (cell != null && (cell.isTruncated() || cell.getUnsupportedReason() != null)) {
                        warnings.add(new CellWarning(rows.size(), row.size(), cell.getUnsupportedReason() == null
                                ? "Value was truncated by the database result reader" : cell.getUnsupportedReason(), cell.getSizeChars(), cell.getLoadedChars()));
                    }
                    row.add(cell == null ? null : cell.getRawValue() instanceof String raw ? raw : cell.getValue());
                }
                rows.add(row);
            }
        }
        return new QueryData(columns, rows, "database-text", response.getExecutionMetrics() == null ? null
                : response.getExecutionMetrics().getTotalDurationMs(), warnings, response.getUpdateCount());
    }
}
