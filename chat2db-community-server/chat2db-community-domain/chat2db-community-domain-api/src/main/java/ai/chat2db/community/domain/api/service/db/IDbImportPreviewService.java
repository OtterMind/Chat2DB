package ai.chat2db.community.domain.api.service.db;

import java.io.File;
import java.util.Map;

/**
 * Bounded import preview with column mapping. The source must already be staged by the server.
 */
public interface IDbImportPreviewService {

    /**
     * Parses a bounded number of rows from a CSV/XLS/XLSX file and returns source fields,
     * sample values, target table columns (type, nullable, default), and a suggested
     * mapping by exact name match. Never writes any data.
     *
     * @param dataSourceId the datasource id.
     * @param databaseName the database name.
     * @param tableName    the target table name.
     * @param file         previously staged upload (extension selects the parser).
     * @param importOptions parser options, including Excel sheet/header configuration.
     * @return preview model.
     */
    Map<String, Object> preview(Long dataSourceId, String databaseName, String schemaName, String tableName,
            File file, Map<String, Object> importOptions);
}
