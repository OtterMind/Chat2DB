package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Bounded import preview and mapped CSV execution for a server-staged file.
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
     * @param file         a previously staged upload.
     * @param csvOptions   CSV options: encoding, delimiter, quote, escape, hasHeader,
     *                     emptyAsNull (ignored for XLS/XLSX).
     * @return preview model.
     */
    Map<String, Object> preview(Long dataSourceId, String databaseName, String schemaName, String tableName,
                                File file, Map<String, Object> csvOptions);

    /**
     * Imports the whole file using the given column mapping. Rows are inserted one by one
     * so a failing row is recorded (source row number + target column + message) and the
     * import continues. Unmapped target columns use DEFAULT or explicit SQL NULL.
     *
     * @param dataSourceId    the datasource id.
     * @param databaseName    the database name.
     * @param tableName       the target table name.
     * @param file            a previously staged upload.
     * @param csvOptions      CSV options (ignored for XLS/XLSX).
     * @param mappings        list of {sourceColumn, targetColumn}; sourceColumn null skips the source field.
     * @param unmappedTarget  DEFAULT or NULL for unmapped target columns.
     * @param context         task lifecycle and cancellation context.
     * @return import result with totals and row-level errors.
     */
    Map<String, Object> execute(Long dataSourceId, String databaseName, String schemaName, String tableName,
                                File file, Map<String, Object> csvOptions,
                                List<Map<String, String>> mappings, String unmappedTarget,
                                TaskExecutionContext context);
}
