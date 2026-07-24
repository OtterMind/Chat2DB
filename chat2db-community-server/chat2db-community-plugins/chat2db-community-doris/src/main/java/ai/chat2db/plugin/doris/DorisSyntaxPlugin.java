package ai.chat2db.plugin.doris;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.plugin.doris.parser.DorisSqlParser;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;

public class DorisSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.DORIS.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new DorisSqlParser();
    }
}
