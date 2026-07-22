package ai.chat2db.plugin.clickhouse;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.plugin.clickhouse.parser.ClickHouseSqlParser;

public class ClickHouseSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {

    public String getDatabaseType() {
        return DatabaseTypeEnum.CLICKHOUSE.name();
    }

    public ISQLParser getSQLParser() {
        return new ClickHouseSqlParser();
    }
}
