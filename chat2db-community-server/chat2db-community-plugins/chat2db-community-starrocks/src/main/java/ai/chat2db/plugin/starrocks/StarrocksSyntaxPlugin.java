package ai.chat2db.plugin.starrocks;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.starrocks.parser.StarrocksSqlParser;

public class StarrocksSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.STARROCKS.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new StarrocksSqlParser();
    }
}
