package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.MetaSchema;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseQueryAllRequest;
import ai.chat2db.community.domain.api.model.request.db.DbMetaDataQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaOperationRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSchemaQueryRequest;

import java.util.List;
import java.util.Map;

/**
 * Exposes database and schema metadata lookup plus database/schema DDL operations.
 */
public interface IDbDatabaseService {

    /**
     * Lists all databases visible to a datasource.
     *
     * @param dbDatabaseQueryAllRequest database lookup parameters.
     * @return database metadata.
     */
    List<Database> queryAll(DbDatabaseQueryAllRequest dbDatabaseQueryAllRequest);

    /**
     * Lists schemas under a database.
     *
     * @param dbSchemaQueryRequest schema lookup parameters.
     * @return schema metadata.
     */
    List<Schema> querySchema(DbSchemaQueryRequest dbSchemaQueryRequest);

    /**
     * Queries combined database and schema metadata for a datasource.
     *
     * @param dbMetaDataQueryRequest database and schema metadata lookup parameters.
     * @return combined database and schema metadata.
     */
    MetaSchema queryDatabaseSchema(DbMetaDataQueryRequest dbMetaDataQueryRequest);

    /**
     * Deletes a database according to the supplied operation parameters.
     *
     * @param dbDatabaseCreateRequest database operation parameters.
     */
    void deleteDatabase(DbDatabaseCreateRequest dbDatabaseCreateRequest);

    /**
     * Builds or executes SQL for creating a database.
     *
     * @param param database definition.
     * @return SQL.
     */
    Sql createDatabase(Database param);

    /**
     * Modifies database metadata according to the supplied operation parameters.
     *
     * @param dbDatabaseCreateRequest database operation parameters.
     */
    void modifyDatabase(DbDatabaseCreateRequest dbDatabaseCreateRequest);

    /**
     * Returns the database default character set and collation reported by the server.
     *
     * @param databaseName the database name.
     * @return map with {@code charset} and {@code collation} keys.
     */
    Map<String, String> databaseInfo(String databaseName);

    /**
     * Generates an ALTER DATABASE statement for a default character set / collation change.
     * No DDL is generated when both values are unchanged.
     *
     * @param databaseName the database name.
     * @param charset      the new default character set.
     * @param collation    the new default collation.
     * @return the SQL preview, or null when nothing changed.
     */
    String previewAlterDatabaseSql(String databaseName, String charset, String collation);

    /**
     * Deletes a schema according to the supplied operation parameters.
     *
     * @param dbSchemaOperationRequest schema operation parameters.
     */
    void deleteSchema(DbSchemaOperationRequest dbSchemaOperationRequest);

    /**
     * Builds or executes SQL for creating a schema.
     *
     * @param schema schema definition.
     * @return SQL.
     */
    Sql createSchema(Schema schema);

    /**
     * Modifies schema metadata according to the supplied operation parameters.
     *
     * @param dbSchemaOperationRequest schema operation parameters.
     */
    void modifySchema(DbSchemaOperationRequest dbSchemaOperationRequest);
}
