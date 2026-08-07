package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.request.db.DbViewDeleteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbViewMetaModifyRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableDdlRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTablePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.community.domain.api.model.view.ModifyViewConfiguration;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.metadata.Table;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Exposes relational view metadata, DDL, and modification contracts.
 */
public interface IDbViewService {

    /**
     * Lists views under a database and schema.
     *
     * @param databaseName database name that scopes the operation.
     * @param schemaName schema name that scopes the operation.
     * @return views.
     */
    List<Table> views(@NotEmpty String databaseName, String schemaName);

    /**
     * Returns metadata for a single view.
     *
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @param tableName view name to query.
     * @return view metadata, or null when no matching view exists.
     */
    Table detail(@NotEmpty String databaseName, String schemaName,String tableName);

    /**
     * Builds SQL for modifying a view definition.
     *
     * @param modifyView view modification model.
     * @return generated SQL for modifying the view.
     */
    String modifySql(ModifyView modifyView);

    /**
     * Builds view modification configuration metadata.
     *
     * @param dbViewMetaModifyRequest view modification metadata parameters.
     * @return configuration metadata used to modify the view.
     */
    ModifyViewConfiguration meta(DbViewMetaModifyRequest dbViewMetaModifyRequest);

    /**
     * Drops a view according to the supplied parameters.
     *
     * @param dbViewDeleteRequest view deletion parameters.
     */
    void drop(DbViewDeleteRequest dbViewDeleteRequest);

    /**
     * Creates a database view by executing the generated CREATE VIEW SQL.
     *
     * @param modifyView view creation model.
     */
    void create(ModifyView modifyView);

    /**
     * Returns view DDL for AI-assisted view generation.
     *
     * @param dbTableDdlRequest table DDL query parameters.
     * @return generated view DDL.
     */
    String getAIViewDDL(DbTableDdlRequest dbTableDdlRequest);

    /**
     * Queries detailed view metadata as table metadata.
     *
     * @param dbTableQueryRequest view query parameters.
     * @return table metadata.
     */
    Table query(DbTableQueryRequest dbTableQueryRequest);

    /**
     * Lists simple view metadata for a datasource scope.
     *
     * @param dbTablePageQueryRequest paged view query parameters.
     * @return simple view metadata.
     */
    List<SimpleTable> queryViews(DbTablePageQueryRequest dbTablePageQueryRequest);
}
