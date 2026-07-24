package ai.chat2db.plugin.h2;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.h2.parser.H2SqlParser;
import ai.chat2db.spi.ISQLParser;

public class H2SyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.H2.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new H2SqlParser();
    }
}
