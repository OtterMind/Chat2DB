package ai.chat2db.plugin.duckdb;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.plugin.duckdb.parser.DuckDBSqlParser;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;

public class DuckDBSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.DUCKDB.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new DuckDBSqlParser();
    }
}
