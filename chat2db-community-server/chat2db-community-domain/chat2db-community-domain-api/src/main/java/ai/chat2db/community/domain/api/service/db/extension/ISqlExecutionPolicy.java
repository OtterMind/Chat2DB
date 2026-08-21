package ai.chat2db.community.domain.api.service.db.extension;

import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext;

import java.util.List;

public interface ISqlExecutionPolicy {

    default String rewriteSql(SqlExecutionContext context, String sql) {
        return sql;
    }

    default Integer maxRows(SqlExecutionContext context, String sql) {
        return null;
    }

    default void beforeExecute(SqlExecutionPlan plan) {
    }

    default void checkpoint(SqlExecutionPlan plan) {
    }

    default boolean includeColumn(SqlResultColumnContext context) {
        return true;
    }

    default boolean canEditResult(SqlExecutionPlan plan, List<SqlResultColumnContext> columns) {
        return true;
    }
}
