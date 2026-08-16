package ai.chat2db.plugin.gaussdb;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.plugin.gaussdb.parser.GaussDBSqlParser;
import ai.chat2db.plugin.postgresql.completion.PostgreSqlInsertEditorHintProvider;
import ai.chat2db.spi.ISQLParser;
import ai.chat2db.spi.ISqlSyntaxPlugin;
import java.util.List;

public class GaussDBSyntaxPlugin implements ISqlSyntaxPlugin {
    @Override
    public String getDatabaseType() {
        return DatabaseTypeEnum.GAUSSDB.name();
    }

    @Override
    public ISQLParser getSQLParser() {
        return new GaussDBSqlParser();
    }

    @Override
    public List<SqlCompletionEditorHint> getSqlEditorHints(DbSqlCompletionRequest request) {
        return new PostgreSqlInsertEditorHintProvider().build(request);
    }
}
