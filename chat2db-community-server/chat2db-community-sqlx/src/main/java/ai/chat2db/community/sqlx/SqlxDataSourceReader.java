package ai.chat2db.community.sqlx;

import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplies the saved connections that the SQLX import copies.
 * <p>
 * Credentials are read through {@code queryDisplayDataSourceById(id, true)}, the decrypted view the
 * connection path itself uses. A stored record keeps its password encrypted, so exporting the raw record
 * would hand SQLX a ciphertext blob instead of the password the user already saved. Nothing here writes
 * the credential anywhere: it only travels to the SQLX child process over stdin.
 */
public final class SqlxDataSourceReader implements SqlxStatusService.DataSourceReader {

    private final IDbWorkspaceDataSourceService dataSources;

    public SqlxDataSourceReader(IDbWorkspaceDataSourceService dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public List<WorkspaceDataSource> read(List<Long> datasourceIds) {
        List<WorkspaceDataSource> found = new ArrayList<>();
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return found;
        }
        for (Long id : datasourceIds) {
            if (id == null) {
                continue;
            }
            WorkspaceDataSource dataSource = dataSources.queryDisplayDataSourceById(id, true);
            if (dataSource != null) {
                found.add(dataSource);
            }
        }
        return found;
    }
}
