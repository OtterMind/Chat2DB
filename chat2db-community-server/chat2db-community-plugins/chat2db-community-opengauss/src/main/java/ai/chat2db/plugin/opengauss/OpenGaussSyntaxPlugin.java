package ai.chat2db.plugin.opengauss;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.opengauss.parser.OpenGaussSqlParser;
import ai.chat2db.spi.ISQLParser;

public class OpenGaussSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.OPENGAUSS.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new OpenGaussSqlParser();
    }
}
