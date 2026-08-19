package ai.chat2db.community.domain.api.service.db;

import java.util.List;
import java.util.Map;

/**
 * Bounded import preview with column mapping (MYSQL-IMPORT-001). Preview and execution
 * share the same file parser, so the mapping shown is exactly what gets imported.
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
     * @param filePath     the uploaded file path (extension selects the parser).
     * @return preview model.
     */
    Map<String, Object> preview(Long dataSourceId, String databaseName, String tableName,
                                String filePath);

    /**
     * Imports the whole file using the given column mapping. Rows are inserted one by one
     * so a failing row is recorded (source row number + target column + message) and the
     * import continues. Unmapped target columns use DEFAULT or explicit SQL NULL.
     *
     * @param dataSourceId    the datasource id.
     * @param databaseName    the database name.
     * @param tableName       the target table name.
     * @param filePath        the uploaded file path.
     * @param mappings        list of {sourceColumn, targetColumn}; sourceColumn null skips the source field.
     * @param unmappedTarget  DEFAULT or NULL for unmapped target columns.
     * @return import result with totals and row-level errors.
     */
    Map<String, Object> execute(Long dataSourceId, String databaseName, String tableName,
                                String filePath,
                                List<Map<String, String>> mappings, String unmappedTarget);
}
