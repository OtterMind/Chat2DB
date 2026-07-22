package ai.chat2db.plugin.postgresql;

import ai.chat2db.spi.ISQLParser;

import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.postgresql.parser.PgsqlSqlParser;
import ai.chat2db.plugin.postgresql.completion.PostgreSqlEditorHintProvider;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import java.util.List;

public class PgsqlSyntaxPlugin implements ISqlSyntaxPlugin {

    public String getDatabaseType(){
        return DatabaseTypeEnum.POSTGRESQL.name();
    }

    public ai.chat2db.spi.ISQLParser getSQLParser(){
        return new PgsqlSqlParser();
    }

    @Override
    public List<SqlCompletionEditorHint> getSqlEditorHints(DbSqlCompletionRequest request) {
        return new PostgreSqlEditorHintProvider().build(request);
    }
}
