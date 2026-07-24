package ai.chat2db.plugin.tdengine;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.plugin.tdengine.parser.TDengineSqlParser;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;

public class TDengineSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.TDENGINE.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new TDengineSqlParser();
    }
}
