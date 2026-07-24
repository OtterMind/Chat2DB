package ai.chat2db.plugin.sqlserver;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.sqlserver.parser.SqlserverSqlParser;

public class SqlServerSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.SQLSERVER.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new SqlserverSqlParser();
    }
}
