package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.task.CsvOptions;

import java.io.File;

/**
 * Database-independent, bounded import preview with column mapping. The preview accepts
 * only a server-staged file, never a client-supplied filesystem path.
 */
public interface IDbImportPreviewService {

    /**
     * Parses a bounded number of rows from a CSV/XLS/XLSX file and returns source fields,
     * target table columns, preview rows, and a suggested mapping by exact name match.
     * Never writes any data.
     *
     * @param dataSourceId the datasource id.
     * @param databaseName the database name.
     * @param schemaName   the schema name.
     * @param tableName    the target table name.
     * @param file         a previously staged upload (extension selects the parser).
     * @return preview model.
     */
    ImportPreview preview(Long dataSourceId, String databaseName, String schemaName,
                          String tableName, File file);

    default ImportPreview preview(Long dataSourceId, String databaseName, String schemaName,
                                  String tableName, File file, CsvOptions csvOptions) {
        return preview(dataSourceId, databaseName, schemaName, tableName, file);
    }
}
