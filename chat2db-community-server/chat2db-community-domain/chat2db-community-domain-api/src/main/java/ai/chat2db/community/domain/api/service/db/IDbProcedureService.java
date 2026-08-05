package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.metadata.Procedure;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Exposes database procedure metadata lookup contracts.
 */
public interface IDbProcedureService {

    /**
     * Lists database procedures under a database and schema.
     *
     * @param databaseName database name that scopes the operation.
     * @param schemaName schema name that scopes the operation.
     * @return procedures.
     */
    List<Procedure> procedures(@NotEmpty String databaseName, String schemaName);

    /**
     * Returns metadata for a single procedure.
     *
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @param procedureName procedure name to query.
     * @return procedure metadata, or null when no matching procedure exists.
     */
    Procedure detail(String databaseName, String schemaName, String procedureName);

    /**
     * Drops a database procedure.
     *
     * @param databaseName database name that scopes the operation.
     * @param schemaName schema name that scopes the operation.
     * @param procedureName procedure name to drop.
     */
    void drop(String databaseName, String schemaName, String procedureName);
}
