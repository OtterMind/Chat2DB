package ai.chat2db.plugin.bigquery;

import ai.chat2db.plugin.bigquery.parser.BigQueryParser;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.ISQLParser;

public class BigQuerySyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {

    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.BIGQUERY.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new BigQueryParser();
    }
}
