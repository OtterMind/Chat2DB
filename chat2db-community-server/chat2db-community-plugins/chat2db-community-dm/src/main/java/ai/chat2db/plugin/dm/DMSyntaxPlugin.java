package ai.chat2db.plugin.dm;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.plugin.dm.parser.DMSqlParser;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;

public class DMSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.DM.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new DMSqlParser();
    }
}
