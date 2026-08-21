package ai.chat2db.community.domain.api.model.sql.extension;

import java.util.Objects;
import java.util.UUID;

public final class SqlExecutionPlan {

    private final SqlExecutionContext context;
    private final String sql;
    private final Integer maxRows;
    private final String executionId;

    public SqlExecutionPlan(SqlExecutionContext context, String sql, Integer maxRows) {
        this(context, sql, maxRows, null);
    }

    public SqlExecutionPlan(SqlExecutionContext context, String sql, Integer maxRows, String executionId) {
        this.context = Objects.requireNonNull(context, "context");
        this.sql = Objects.requireNonNull(sql, "sql");
        if (maxRows != null && maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be greater than zero");
        }
        this.maxRows = maxRows;
        this.executionId = executionId == null || executionId.isBlank()
                ? UUID.randomUUID().toString()
                : executionId;
    }

    public SqlExecutionContext getContext() {
        return context;
    }

    public String getSql() {
        return sql;
    }

    public Integer getMaxRows() {
        return maxRows;
    }

    public String getExecutionId() {
        return executionId;
    }
}
