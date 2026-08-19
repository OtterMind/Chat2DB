package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;

import java.util.List;

/**
 * Manages persisted datasource definitions in the active workspace.
 */
public interface IDbWorkspaceDataSourceService {

    PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest request);

    WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword);

    WorkspaceDataSource queryDisplayDataSourceById(Long id, Boolean requestPassword);

    /**
     * Tests a datasource connection using saved credentials when required.
     *
     * @param request datasource pre-connection request.
     */
    void preConnect(DbDataSourcePreConnectRequest request);

    /**
     * Creates a datasource and returns its display representation.
     *
     * @param dataSource datasource to create.
     * @return created datasource prepared for the web boundary.
     */
    WorkspaceDataSource createDataSource(WorkspaceDataSource dataSource);

    /**
     * Updates a datasource, clears its runtime connection, and returns its display representation.
     *
     * @param dataSource datasource to update.
     * @return updated datasource prepared for the web boundary.
     */
    WorkspaceDataSource updateDataSource(WorkspaceDataSource dataSource);

    /**
     * Updates only the shared identity color of a datasource.
     *
     * @param id datasource identifier.
     * @param identityColor nullable {@code #RRGGBB} color; null clears the custom color.
     * @return updated datasource identity fields prepared for the web boundary.
     */
    WorkspaceDataSource updateDataSourceIdentityColor(Long id, String identityColor);

    /**
     * Deletes a datasource and clears its runtime connection.
     *
     * @param id datasource identifier.
     */
    void deleteDataSource(Long id);

    List<WorkspaceDataSource> exportDataSources(List<Long> datasourceIds);

    List<WorkspaceDataSource> exportDisplayDataSources(List<Long> datasourceIds);
}
