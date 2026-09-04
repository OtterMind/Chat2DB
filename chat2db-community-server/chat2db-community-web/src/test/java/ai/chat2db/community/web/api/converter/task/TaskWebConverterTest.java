package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.enums.ExportSizeEnum;
import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.web.api.model.request.task.TaskExportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void distinguishesSqlExportScopesInTaskNames() {
        TaskExportRequest structure = exportRequest(TaskType.SQL_EXPORT.name(), "app", "orders");
        structure.setScope(ExportScopeTypeEnum.SCHEMA.name());
        TaskExportRequest data = exportRequest(TaskType.SQL_EXPORT.name(), "app", "orders");
        data.setScope(ExportScopeTypeEnum.TABLE.name());
        TaskExportRequest structureAndData = exportRequest(TaskType.SQL_EXPORT.name(), "app", "orders");
        structureAndData.setScope(ExportScopeTypeEnum.ALL.name());

        assertEquals("Export database structure - app.orders",
                converter.exportRequest2spec(structure).getTaskName());
        assertEquals("Export database data - app.orders",
                converter.exportRequest2spec(data).getTaskName());
        assertEquals("Export database structure and data - app.orders",
                converter.exportRequest2spec(structureAndData).getTaskName());
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

        assertEquals("Import table data - app.public.orders", dataSpec.getTaskName());
        assertEquals("public", dataSpec.getTarget().getSchemaName());
        assertEquals("Import SQL file - app.public.orders", sqlSpec.getTaskName());
    }

    @Test
    void preservesValidatedCsvOptionsForImportAndExportTasks() {
        CsvOptions csvOptions = CsvOptions.builder()
                .encoding("AUTO")
                .delimiter("|")
                .quote("\"")
                .escape("\\")
                .newline("CRLF")
                .hasHeader(true)
                .emptyAsNull(true)
                .build();
        TaskImportRequest importRequest = importRequest(TaskType.DATA_FILE_IMPORT.name());
        importRequest.setCsvOptions(csvOptions);
        importRequest.setMappings(List.of(Map.of("sourceColumn", "note", "targetColumn", "note")));
        importRequest.setUnmappedTarget("NULL");
        TaskExportRequest exportRequest = exportRequest(TaskType.TABLE_DATA_EXPORT.name(), "app", "orders");
        exportRequest.setCsvOptions(csvOptions);

        ImportTaskSpec importSpec = converter.importRequest2spec(importRequest);
        ExportTaskSpec exportSpec = converter.exportRequest2spec(exportRequest);

        assertEquals("AUTO", importSpec.getCsvOptions().getEncoding());
        assertEquals("\\", importSpec.getCsvOptions().getEscape());
        assertEquals(List.of(Map.of("sourceColumn", "note", "targetColumn", "note")),
                importSpec.getMappings());
        assertEquals("NULL", importSpec.getUnmappedTarget());
        assertEquals("CRLF", exportSpec.getCsvOptions().getNewline());
    }

    @Test
    void rejectsUnsupportedCsvOptionsBeforeTaskSubmission() {
        TaskImportRequest request = importRequest(TaskType.DATA_FILE_IMPORT.name());
        request.setCsvOptions(CsvOptions.builder()
                .encoding("UTF-8")
                .delimiter(",")
                .quote("\"")
                .escape("\n")
                .newline("LF")
                .hasHeader(true)
                .emptyAsNull(true)
                .build());

        assertEquals("import.preview.invalidCsvOptions",
                assertThrows(BusinessException.class, () -> converter.importRequest2spec(request)).getCode());

        request.setCsvOptions(CsvOptions.builder()
                .encoding("NO_SUCH_CHARSET")
                .delimiter(",")
                .quote("\"")
                .escape("\"")
                .newline("LF")
                .hasHeader(true)
                .emptyAsNull(true)
                .build());
        assertEquals("import.preview.invalidEncoding",
                assertThrows(BusinessException.class, () -> converter.importRequest2spec(request)).getCode());
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
        request.setSchemaName("public");
        request.setTableName("orders");
        request.setSourceFile("/tmp/orders.csv");
        request.setFormat(TaskType.SQL_FILE_IMPORT.name().equals(taskType)
                ? TaskFileFormat.SQL.name() : TaskFileFormat.CSV.name());
        return request;
    }
}
