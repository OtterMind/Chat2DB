package ai.chat2db.plugin.redshift;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.redshift.parser.RedShiftSqlParser;

public class RedShiftSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.REDSHIFT.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new RedShiftSqlParser();
    }
}
