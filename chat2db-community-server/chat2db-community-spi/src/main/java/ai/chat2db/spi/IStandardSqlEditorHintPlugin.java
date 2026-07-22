package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.spi.completion.StandardSqlInsertEditorHintProvider;
import java.util.List;

/**
 * Explicit opt-in for relational dialects that support the standard
 * {@code INSERT INTO table (columns) VALUES (...)} editor hints.
 */
public interface IStandardSqlEditorHintPlugin extends ISqlSyntaxPlugin {

    @Override
    default List<SqlCompletionEditorHint> getSqlEditorHints(DbSqlCompletionRequest request) {
        return new StandardSqlInsertEditorHintProvider(getDatabaseType()).build(request);
    }
}
