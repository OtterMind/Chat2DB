package ai.chat2db.plugin.hive;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.hive.parser.HiveSqlParser;

public class HiveSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.HIVE.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new HiveSqlParser();
    }
}
