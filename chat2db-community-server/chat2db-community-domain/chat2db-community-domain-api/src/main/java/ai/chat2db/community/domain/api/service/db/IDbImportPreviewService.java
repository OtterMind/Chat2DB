package ai.chat2db.community.domain.api.service.db;

import java.io.File;
import java.util.Map;

/**
 * Bounded import preview with column mapping (MYSQL-IMPORT-001). The preview accepts
 * only a server-staged file, never a renderer-supplied filesystem path.
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
     * @param file         a previously staged upload (extension selects the parser).
     * @return preview model.
     */
    Map<String, Object> preview(Long dataSourceId, String databaseName, String tableName,
                                File file);
}
