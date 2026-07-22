package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import java.util.List;

/**
 * Provides SQL parser and completion capabilities for one database dialect.
 */
public interface ISqlSyntaxPlugin {

    /**
     * Returns the database type handled by this syntax plugin.
     *
     * @return database type value for the dialect.
     */
    String getDatabaseType();


    /**
     * Returns the SQL parser for this dialect.
     *
     * @return dialect-specific SQL parser.
     */
    ISQLParser getSQLParser();

    /**
     * Returns the SQL completion provider for this dialect.
     *
     * @return completion provider when supported; otherwise an unsupported provider.
     */
    default ISqlCompletionProvider getSqlCompletionProvider() {
        return ISqlCompletionProvider.unsupported(getDatabaseType());
    }

    /**
     * Returns editor-only hints without replacing the dialect's existing completion engine.
     *
     * @param request completion request containing SQL, cursor, and metadata context.
     * @return non-executable editor hints, or an empty list when unsupported.
     */
    default List<SqlCompletionEditorHint> getSqlEditorHints(DbSqlCompletionRequest request) {
        return List.of();
    }
}
