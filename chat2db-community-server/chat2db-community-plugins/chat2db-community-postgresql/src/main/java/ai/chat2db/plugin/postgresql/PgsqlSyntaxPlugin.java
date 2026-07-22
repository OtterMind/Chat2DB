package ai.chat2db.plugin.postgresql;

import ai.chat2db.spi.ISQLParser;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.postgresql.parser.PgsqlSqlParser;

public class PgsqlSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {

    public String getDatabaseType(){
        return DatabaseTypeEnum.POSTGRESQL.name();
    }

    public ai.chat2db.spi.ISQLParser getSQLParser(){
        return new PgsqlSqlParser();
    }
}
