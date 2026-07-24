package ai.chat2db.plugin.oceanbase;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.oceanbase.parser.OceanBaseSqlParser;
import ai.chat2db.spi.ISQLParser;

public class OceanBaseSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.OCEANBASE.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new OceanBaseSqlParser();
    }
}
