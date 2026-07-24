package ai.chat2db.plugin.cockroachdb;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.plugin.cockroachdb.parser.CockroachSqlParser;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;

public class CockRoachSyntaxPlugin implements ISqlSyntaxPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.COCKROACH.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new CockroachSqlParser();
    }
}
