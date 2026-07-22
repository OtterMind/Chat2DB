package ai.chat2db.plugin.kingbase;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.kingbase.parser.KingBaseSqlParser;
import ai.chat2db.spi.ISQLParser;

public class KingBaseSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.KINGBASE.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new KingBaseSqlParser();
    }
}
