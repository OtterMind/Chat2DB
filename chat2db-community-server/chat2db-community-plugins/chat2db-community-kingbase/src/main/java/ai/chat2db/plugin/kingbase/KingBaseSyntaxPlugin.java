package ai.chat2db.plugin.kingbase;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.kingbase.parser.KingBaseSqlParser;
import ai.chat2db.spi.ISQLParser;

public class KingBaseSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.KINGBASE.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new KingBaseSqlParser();
    }
}
