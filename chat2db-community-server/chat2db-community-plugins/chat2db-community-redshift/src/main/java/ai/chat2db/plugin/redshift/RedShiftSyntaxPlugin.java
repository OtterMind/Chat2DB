package ai.chat2db.plugin.redshift;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.redshift.parser.RedShiftSqlParser;

public class RedShiftSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.REDSHIFT.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new RedShiftSqlParser();
    }
}
