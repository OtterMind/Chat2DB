package ai.chat2db.plugin.xugudb;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.xugudb.parser.XUGUDBSqlParser;

public class XUGUDBSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.XUGUDB.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new XUGUDBSqlParser();
    }
}
