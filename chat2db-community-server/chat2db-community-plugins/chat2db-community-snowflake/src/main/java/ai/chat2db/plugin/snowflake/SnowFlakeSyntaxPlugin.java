package ai.chat2db.plugin.snowflake;

import ai.chat2db.spi.ISQLParser;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.plugin.snowflake.parser.SnowFlakeSqlParser;

public class SnowFlakeSyntaxPlugin implements ISqlSyntaxPlugin {

    public String getDatabaseType(){
        return ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum.SNOWFLAKE.name();
    }

    public ai.chat2db.spi.ISQLParser getSQLParser(){
        return new SnowFlakeSqlParser();
    }
}
