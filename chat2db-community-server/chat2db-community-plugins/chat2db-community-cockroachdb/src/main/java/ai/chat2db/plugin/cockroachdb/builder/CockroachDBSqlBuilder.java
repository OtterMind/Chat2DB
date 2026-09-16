package ai.chat2db.plugin.cockroachdb.builder;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import org.apache.commons.lang3.StringUtils;

import static ai.chat2db.plugin.cockroachdb.constant.CockroachDBSqlBuilderConstants.SINGLE_ROW_LIMIT_SQL;

public class CockroachDBSqlBuilder extends PostgreSQLSqlBuilder {

    @Override
    public String buildCreateDatabase(Database database) {
        String name = PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName());
        String sql = "CREATE DATABASE " + name;
        if (StringUtils.isNotBlank(database.getComment())) {
            sql += "; COMMENT ON DATABASE " + name + " IS '"
                    + PostgreSQLIdentifierProcessor.INSTANCE.escapeString(database.getComment()) + "';";
        }
        return sql;
    }

    @Override
    protected String appendSingleRowLimit(String operationType, String tableName, String whereClause, String sql) {
        return sql + SINGLE_ROW_LIMIT_SQL;
    }
}
