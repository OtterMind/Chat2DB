package ai.chat2db.plugin.cockroachdb;

import ai.chat2db.plugin.cockroachdb.builder.CockroachDBSqlBuilder;
import ai.chat2db.plugin.postgresql.PostgreSQLMetaData;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;

public class CockroachDBMetaData extends PostgreSQLMetaData implements IDbMetaData {
    @Override
    public ISqlBuilder getSqlBuilder() {
        // Use the CockroachDB builder so the no-PK single-row guard uses the hidden
        // `rowid` column instead of PostgreSQL's `ctid` (CockroachDB has no ctid).
        return new CockroachDBSqlBuilder();
    }
}
