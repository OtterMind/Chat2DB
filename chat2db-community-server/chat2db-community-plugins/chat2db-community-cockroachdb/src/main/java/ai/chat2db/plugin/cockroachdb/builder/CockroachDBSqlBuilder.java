package ai.chat2db.plugin.cockroachdb.builder;

import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import org.apache.commons.lang3.StringUtils;

/**
 * CockroachDB SQL builder. CockroachDB has no PostgreSQL {@code ctid} system
 * column; tables without an explicit primary key get a hidden {@code rowid}
 * column (unique), so the no-PK single-row UPDATE/DELETE guard uses {@code rowid}
 * instead of {@code ctid}.
 */
public class CockroachDBSqlBuilder extends PostgreSQLSqlBuilder {

    private static final String SQL_WHERE_ROWID_IN_OPEN_PAREN_SELECT_ROWID_FROM =
            " where rowid in (select rowid from ";
    private static final String VALUE_LIMIT_1_CLOSE_PAREN = " limit 1)";

    @Override
    protected String appendSingleRowLimit(String operationType, String tableName, String whereClause, String sql) {
        if (StringUtils.isBlank(whereClause) || !sql.endsWith(whereClause)) {
            return sql;
        }
        String body = sql.substring(0, sql.length() - whereClause.length());
        return body + SQL_WHERE_ROWID_IN_OPEN_PAREN_SELECT_ROWID_FROM + tableName + whereClause
                + VALUE_LIMIT_1_CLOSE_PAREN;
    }
}
