package ai.chat2db.community.domain.api.service.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;

/**
 * Provides AI tool operations over datasource metadata and SQL execution.
 *
 * <p>Methods return a serialized AiToolResult JSON string with success, summary, data, and errorCode fields.
 */
public interface IAiToolService {

    /**
     * Returns datasource metadata for AI tools.
     *
     * @param aiToolContextRequest AI tool context parameters.
     * @return serialized AiToolResult JSON string.
     */
    String listAllDataSources(AiToolContextRequest aiToolContextRequest);

    /**
     * Returns table metadata for AI tools.
     *
     * @param aiListTablesRequest AI table listing parameters.
     * @return serialized AiToolResult JSON string.
     */
    String listAllTables(AiListTablesRequest aiListTablesRequest);

    /**
     * Returns database metadata for AI tools.
     *
     * @param dataSourceId datasource identifier.
     * @param aiToolContextRequest AI tool context parameters.
     * @return serialized AiToolResult JSON string.
     */
    String listAllDatabases(Long dataSourceId, AiToolContextRequest aiToolContextRequest);

    /**
     * Returns schema metadata for AI tools.
     *
     * @param databaseName database name that scopes the lookup.
     * @param dataSourceId datasource identifier.
     * @param aiToolContextRequest AI tool context parameters.
     * @return serialized AiToolResult JSON string.
     */
    String listAllSchemas(String databaseName, Long dataSourceId, AiToolContextRequest aiToolContextRequest);

    /**
     * Executes SQL for an AI tool request.
     *
     * @param aiExecuteSqlRequest AI SQL execution parameters.
     * @return serialized AiToolResult JSON string.
     */
    String executeSql(AiExecuteSqlRequest aiExecuteSqlRequest);

    /**
     * Returns table schema metadata for AI tools.
     *
     * @param aiGetTablesSchemaRequest AI table schema lookup parameters.
     * @return serialized AiToolResult JSON string.
     */
    String getTablesSchema(AiGetTablesSchemaRequest aiGetTablesSchemaRequest);
}
