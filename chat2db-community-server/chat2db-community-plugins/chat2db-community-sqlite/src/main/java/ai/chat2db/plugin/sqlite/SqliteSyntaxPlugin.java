package ai.chat2db.plugin.sqlite;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.sqlite.parser.SqliteSqlParser;

public class SqliteSyntaxPlugin implements ISqlSyntaxPlugin{

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.SQLITE.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new SqliteSqlParser();
    }
}
