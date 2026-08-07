package ai.chat2db.plugin.redshift.builder;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.chat2db.plugin.redshift.identifier.RedshiftIdentifierProcessor;

/**
 * Redshift SQL builder. Redshift rejects the PostgreSQL {@code LC_CTYPE}/
 * {@code LC_COLLATE} clauses (and the base builder always prepends a dangling
 * {@code WITH}); emit a bare {@code CREATE DATABASE}.
 */
public class RedshiftSqlBuilder extends PostgreSQLSqlBuilder {

    @Override
    public String buildCreateDatabase(Database database) {
        return "CREATE DATABASE " + RedshiftIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName());
    }
}
