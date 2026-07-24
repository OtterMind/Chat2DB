package ai.chat2db.plugin.mariadb;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.mariadb.parser.MariadbSqlParser;

public class MariadbSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.MARIADB.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new MariadbSqlParser();
    }
}
