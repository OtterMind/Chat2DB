package ai.chat2db.community.domain.api.service.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiExecuteSqlRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiGetTablesSchemaRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiListTablesRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiToolContextRequest;
import ai.chat2db.community.domain.api.model.result.AiExecuteSqlResult;

/**
 * Provides AI tool operations over datasource metadata and SQL execution.
 */
public interface IAiToolService {

    /**
     * Returns datasource context text for AI tools.
     *
     * @param aiToolContextRequest AI tool context parameters.
     * @return datasource context text.
     */
    String listAllDataSources(AiToolContextRequest aiToolContextRequest);

    /**
     * Returns table context text for AI tools.
     *
     * @param aiListTablesRequest AI table listing parameters.
     * @return table context text.
     */
    String listAllTables(AiListTablesRequest aiListTablesRequest);

    /**
     * Returns database context text for AI tools.
     *
     * @param dataSourceId datasource identifier.
     * @param aiToolContextRequest AI tool context parameters.
     * @return database context text.
     */
    String listAllDatabases(Long dataSourceId, AiToolContextRequest aiToolContextRequest);

    /**
     * Returns schema context text for AI tools.
     *
     * @param databaseName database name that scopes the lookup.
     * @param dataSourceId datasource identifier.
     * @param aiToolContextRequest AI tool context parameters.
     * @return schema context text.
     */
    String listAllSchemas(String databaseName, Long dataSourceId, AiToolContextRequest aiToolContextRequest);

    /**
     * Executes SQL for an AI tool request.
     *
     * @param aiExecuteSqlRequest AI SQL execution parameters.
     * @return SQL execution context text.
     */
    String executeSql(AiExecuteSqlRequest aiExecuteSqlRequest);

    /**
     * Executes SQL and preserves structured approval metadata for Connector transports.
     *
     * @param aiExecuteSqlRequest AI SQL execution parameters.
     * @return SQL execution text and any approval decision metadata.
     */
    AiExecuteSqlResult executeSqlResult(AiExecuteSqlRequest aiExecuteSqlRequest);

    /**
     * Returns table schema context text for AI tools.
     *
     * @param aiGetTablesSchemaRequest AI table schema lookup parameters.
     * @return table schema context text.
     */
    String getTablesSchema(AiGetTablesSchemaRequest aiGetTablesSchemaRequest);
}
