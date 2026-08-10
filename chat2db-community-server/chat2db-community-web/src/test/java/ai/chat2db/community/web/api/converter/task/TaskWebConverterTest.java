package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.enums.ExportSizeEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.web.api.model.request.task.TaskExportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskWebConverterTest {

    private final TaskWebConverter converter = new TaskWebConverter();

    @Test
    void namesAllQueryResultsExportWithItsFormatAndTable() {
        TaskExportRequest request = exportRequest(TaskType.QUERY_RESULT_EXPORT.name(), "app", "orders");
        request.setExportSize(ExportSizeEnum.ALL.name());
        request.setFormat(TaskFileFormat.SQL.name());

        ExportTaskSpec spec = converter.exportRequest2spec(request);

        assertEquals("Export all query results as INSERT SQL - orders", spec.getTaskName());
        assertEquals("orders", spec.getTarget().getTableName());
    }

    @Test
    void namesCurrentPageExportWithItsFormatAndTable() {
        TaskExportRequest request = exportRequest(TaskType.QUERY_RESULT_EXPORT.name(), "app", "orders");
        request.setExportSize(ExportSizeEnum.CURRENT_PAGE.name());
        request.setFormat(TaskFileFormat.XLSX.name());

        ExportTaskSpec spec = converter.exportRequest2spec(request);

        assertEquals("Export current page results as XLSX - orders", spec.getTaskName());
    }

    @Test
    void namesTableDataExportWithItsQualifiedTable() {
        TaskExportRequest request = exportRequest(TaskType.TABLE_DATA_EXPORT.name(), "app", "orders");

        ExportTaskSpec spec = converter.exportRequest2spec(request);

        assertEquals("Export table data - app.orders", spec.getTaskName());
    }

    @Test
    void namesDatabaseSqlExportWithoutInventingATable() {
        TaskExportRequest request = exportRequest(TaskType.SQL_EXPORT.name(), "app", null);

        ExportTaskSpec spec = converter.exportRequest2spec(request);

        assertEquals("Export database SQL - app", spec.getTaskName());
    }

    @Test
    void fallsBackToDatabaseForQueryResultWithoutATable() {
        TaskExportRequest request = exportRequest(TaskType.QUERY_RESULT_EXPORT.name(), "app", null);

        ExportTaskSpec spec = converter.exportRequest2spec(request);

        assertEquals("Export query result as CSV - app", spec.getTaskName());
    }

    @Test
    void preservesAnExplicitExportTaskName() {
        TaskExportRequest request = exportRequest(TaskType.TABLE_DATA_EXPORT.name(), "app", "orders");
        request.setTaskName("Quarterly orders archive");

        ExportTaskSpec spec = converter.exportRequest2spec(request);

        assertEquals("Quarterly orders archive", spec.getTaskName());
    }

    @Test
    void distinguishesDataAndSqlFileImports() {
        TaskImportRequest dataRequest = importRequest(TaskType.DATA_FILE_IMPORT.name());
        TaskImportRequest sqlRequest = importRequest(TaskType.SQL_FILE_IMPORT.name());

        ImportTaskSpec dataSpec = converter.importRequest2spec(dataRequest);
        ImportTaskSpec sqlSpec = converter.importRequest2spec(sqlRequest);

        assertEquals("Import table data - app.orders", dataSpec.getTaskName());
        assertEquals("Import SQL file - app.orders", sqlSpec.getTaskName());
    }

    private TaskExportRequest exportRequest(String taskType, String databaseName, String tableName) {
        TaskExportRequest request = new TaskExportRequest();
        request.setTaskType(taskType);
        request.setDatabaseName(databaseName);
        request.setTableNames(tableName == null ? null : List.of(tableName));
        request.setFormat(TaskFileFormat.CSV.name());
        return request;
    }

    private TaskImportRequest importRequest(String taskType) {
        TaskImportRequest request = new TaskImportRequest();
        request.setTaskType(taskType);
        request.setDatabaseName("app");
        request.setTableName("orders");
        request.setSourceFile("/tmp/orders.csv");
        request.setFormat(TaskType.SQL_FILE_IMPORT.name().equals(taskType)
                ? TaskFileFormat.SQL.name() : TaskFileFormat.CSV.name());
        return request;
    }
}
