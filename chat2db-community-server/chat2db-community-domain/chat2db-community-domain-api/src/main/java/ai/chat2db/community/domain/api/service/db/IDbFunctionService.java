package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.metadata.Function;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Exposes database function metadata lookup contracts.
 */
public interface IDbFunctionService {

    /**
     * Lists database functions under a database and schema.
     *
     * @param databaseName database name that scopes the operation.
     * @param schemaName schema name that scopes the operation.
     * @return functions.
     */
    List<Function> functions(@NotEmpty String databaseName, String schemaName);

    /**
     * Returns metadata for a single function.
     *
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @param functionName function name to query.
     * @return function metadata, or null when no matching function exists.
     */
    Function detail(String databaseName, String schemaName, String functionName);

    /**
     * Drops a database function.
     *
     * @param databaseName database name that scopes the operation.
     * @param schemaName schema name that scopes the operation.
     * @param functionName function name to drop.
     */
    void drop(String databaseName, String schemaName, String functionName);
}
