package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.metadata.Trigger;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Exposes database trigger metadata lookup contracts.
 */
public interface IDbTriggerService {

    /**
     * Lists database triggers under a database and schema.
     *
     * @param databaseName database name that scopes the operation.
     * @param schemaName schema name that scopes the operation.
     * @return triggers.
     */
    List<Trigger> triggers(@NotEmpty String databaseName, String schemaName);

    /**
     * Returns metadata for a single trigger.
     *
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @param triggerName trigger name to query.
     * @return trigger metadata, or null when no matching trigger exists.
     */
    Trigger detail(String databaseName, String schemaName, String triggerName);

    /**
     * Drops a database trigger.
     *
     * @param databaseName database name that scopes the operation.
     * @param schemaName schema name that scopes the operation.
     * @param triggerName trigger name to drop.
     */
    void drop(String databaseName, String schemaName, String triggerName);
}
