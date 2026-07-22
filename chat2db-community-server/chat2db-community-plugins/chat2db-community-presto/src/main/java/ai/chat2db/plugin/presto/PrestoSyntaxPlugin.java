package ai.chat2db.plugin.presto;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.presto.parser.PrestoSqlParser;

public class PrestoSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.PRESTO.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new PrestoSqlParser();
    }
}
