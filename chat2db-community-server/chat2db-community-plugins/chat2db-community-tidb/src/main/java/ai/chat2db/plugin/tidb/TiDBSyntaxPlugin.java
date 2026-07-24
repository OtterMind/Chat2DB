package ai.chat2db.plugin.tidb;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.tidb.parser.TiDBSqlParser;

public class TiDBSyntaxPlugin  implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.TIDB.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new TiDBSqlParser();
    }
}
