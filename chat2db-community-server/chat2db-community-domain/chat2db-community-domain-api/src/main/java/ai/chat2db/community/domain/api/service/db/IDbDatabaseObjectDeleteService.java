package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.db.DatabaseObjectDeletePrepare;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseDeletePrepareRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDatabaseObjectDeleteExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaDeletePrepareRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTablespaceDeletePrepareRequest;

/**
 * Prepares and executes database-object deletion operations.
 */
public interface IDbDatabaseObjectDeleteService {

    /**
     * Prepares database deletion and returns affected object metadata.
     *
     * @param dbDatabaseDeletePrepareRequest database deletion preparation parameters.
     * @return database object deletion preparation result.
     */
    DatabaseObjectDeletePrepare prepareDatabaseDelete(DbDatabaseDeletePrepareRequest dbDatabaseDeletePrepareRequest);

    /**
     * Prepares schema deletion and returns affected object metadata.
     *
     * @param dbSchemaDeletePrepareRequest schema deletion preparation parameters.
     * @return database object deletion preparation result.
     */
    DatabaseObjectDeletePrepare prepareSchemaDelete(DbSchemaDeletePrepareRequest dbSchemaDeletePrepareRequest);

    /**
     * Executes a prepared database deletion.
     *
     * @param dbDatabaseObjectDeleteExecuteRequest database object deletion execution parameters.
     */
    void executeDatabaseDelete(DbDatabaseObjectDeleteExecuteRequest dbDatabaseObjectDeleteExecuteRequest);

    /**
     * Executes a prepared schema deletion.
     *
     * @param dbDatabaseObjectDeleteExecuteRequest database object deletion execution parameters.
     */
    void executeSchemaDelete(DbDatabaseObjectDeleteExecuteRequest dbDatabaseObjectDeleteExecuteRequest);

    /**
     * Prepares tablespace deletion, surfacing occupying tables so the UI can block a non-empty
     * tablespace and require name-typing for an empty one.
     */
    DatabaseObjectDeletePrepare prepareTablespaceDelete(DbTablespaceDeletePrepareRequest param);

    /**
     * Executes a prepared tablespace deletion (empty tablespace only).
     */
    void executeTablespaceDelete(DbDatabaseObjectDeleteExecuteRequest param);
}
