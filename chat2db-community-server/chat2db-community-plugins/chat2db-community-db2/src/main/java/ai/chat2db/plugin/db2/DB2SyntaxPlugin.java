package ai.chat2db.plugin.db2;

import ai.chat2db.plugin.db2.parser.DB2SqlParser;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;

public class DB2SyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.DB2.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new DB2SqlParser();
    }
}
