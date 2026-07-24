package ai.chat2db.plugin.presto;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.presto.parser.PrestoSqlParser;

public class PrestoSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.PRESTO.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new PrestoSqlParser();
    }
}
