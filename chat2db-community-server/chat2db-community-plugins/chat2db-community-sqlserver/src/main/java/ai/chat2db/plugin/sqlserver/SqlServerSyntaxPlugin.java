package ai.chat2db.plugin.sqlserver;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.sqlserver.parser.SqlserverSqlParser;

public class SqlServerSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.SQLSERVER.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new SqlserverSqlParser();
    }
}
