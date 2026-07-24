package ai.chat2db.plugin.gaussdb;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.gaussdb.parser.GaussDBSqlParser;

public class GaussDBSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.GAUSSDB.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new GaussDBSqlParser();
    }
}
