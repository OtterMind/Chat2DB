package ai.chat2db.community.domain.api.service.ai;

import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;

import java.util.List;

/**
 * Provides AI tool operations over datasource metadata and SQL execution.
 */
public interface IAiToolService {

    /**
     * Returns datasource metadata for AI tools.
     *
     * @param aiToolContextRequest AI tool context parameters.
     * @return datasource metadata.
     */
    List<WorkspaceDataSource> listAllDataSources(AiToolContextRequest aiToolContextRequest);

    /**
     * Returns table metadata for AI tools.
     *
     * @param aiListTablesRequest AI table listing parameters.
     * @return table metadata.
     */
    List<SimpleTable> listAllTables(AiListTablesRequest aiListTablesRequest);

    /**
     * Returns database metadata for AI tools.
     *
     * @param dataSourceId datasource identifier.
     * @param aiToolContextRequest AI tool context parameters.
     * @return database metadata.
     */
    List<Database> listAllDatabases(Long dataSourceId, AiToolContextRequest aiToolContextRequest);

    /**
     * Returns schema metadata for AI tools.
     *
     * @param databaseName database name that scopes the lookup.
     * @param dataSourceId datasource identifier.
     * @param aiToolContextRequest AI tool context parameters.
     * @return schema metadata.
     */
    List<Schema> listAllSchemas(String databaseName, Long dataSourceId, AiToolContextRequest aiToolContextRequest);

    /**
     * Executes SQL for an AI tool request.
     *
     * @param aiExecuteSqlRequest AI SQL execution parameters.
     * @return SQL execution result sets.
     */
    List<ExecuteResponse> executeSql(AiExecuteSqlRequest aiExecuteSqlRequest);

    /**
     * Returns table schema metadata for AI tools.
     *
     * @param aiGetTablesSchemaRequest AI table schema lookup parameters.
     * @return table schema results.
     */
    List<TableSchemaResult> getTablesSchema(AiGetTablesSchemaRequest aiGetTablesSchemaRequest);
}
