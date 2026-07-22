package ai.chat2db.plugin.oracle;

import ai.chat2db.spi.ISQLParser;

import ai.chat2db.plugin.oracle.parser.OracleSqlParser;

public class OracleSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {
    public String getDatabaseType(){
        return ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum.ORACLE.name();
    }
    public ai.chat2db.spi.ISQLParser getSQLParser(){
        return new OracleSqlParser();
    }
}
