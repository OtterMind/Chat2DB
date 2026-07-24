package ai.chat2db.plugin.sundb;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.sundb.parser.SUNDBSqlParser;

public class SUNDBSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.SUNDB.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new SUNDBSqlParser();
    }
}
