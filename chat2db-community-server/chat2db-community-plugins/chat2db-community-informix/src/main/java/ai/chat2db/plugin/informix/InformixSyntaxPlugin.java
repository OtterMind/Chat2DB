package ai.chat2db.plugin.informix;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.informix.parser.InformixSqlParser;
import ai.chat2db.spi.ISQLParser;

public class InformixSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.INFOMIX.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new InformixSqlParser();
    }
}
