package ai.chat2db.plugin.cockroachdb.builder;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;

public class CockroachDBSqlBuilder extends PostgreSQLSqlBuilder {

    @Override
    public String buildCreateDatabase(Database database) {
        return "CREATE DATABASE " + PostgreSQLIdentifierProcessor.INSTANCE.quoteIdentifierAlways(database.getName());
    }
}
