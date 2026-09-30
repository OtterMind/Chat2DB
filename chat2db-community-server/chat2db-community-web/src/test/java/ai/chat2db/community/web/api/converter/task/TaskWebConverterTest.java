package ai.chat2db.community.web.api.converter.task;

import ai.chat2db.community.domain.api.enums.ExportSizeEnum;
import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportFinalizationOptions;
import ai.chat2db.community.domain.api.model.task.ImportRollbackOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportStagingPolicy;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.web.api.model.request.task.TaskExportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportTableSourceRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskWebConverterTest {

    @Test
    void importPreviewPreservesStagedSourceAndNullStrategy() {
        var request = new ai.chat2db.community.web.api.model.request.task.TaskImportRequest();
        request.setClientSubmissionId("  import-attempt-1  ");
        request.setFileId("staged-source");
        request.setFormat("CSV");
        request.setUnmappedTarget(ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy.NULL);
        var result = new TaskWebConverter().importRequest2spec(request);
        assertEquals("import-attempt-1", result.getClientSubmissionId());
        assertEquals("staged-source", result.getImportFileId());
        assertEquals(ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy.NULL, result.getUnmappedTarget());
    }

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
        dataRequest.setConfirmedNoStrongRelations(true);
        TaskImportRequest sqlRequest = importRequest(TaskType.SQL_FILE_IMPORT.name());

        ImportTaskSpec dataSpec = converter.importRequest2spec(dataRequest);
        ImportTaskSpec sqlSpec = converter.importRequest2spec(sqlRequest);

        assertEquals("Import table data - app.public.orders", dataSpec.getTaskName());
        assertEquals("public", dataSpec.getTarget().getSchemaName());
        assertEquals(Boolean.TRUE, dataSpec.getConfirmedNoStrongRelations());
        assertNull(dataSpec.getScope());
        assertEquals("Import SQL file - app.public.orders", sqlSpec.getTaskName());
        assertNull(sqlSpec.getScope());
    }

    @Test
    void rejectsUnsupportedDataSourceImportScope() {
        TaskImportRequest request = importRequest(TaskType.DATA_FILE_IMPORT.name());
        request.setScope("DATA_SOURCE");

        assertThrows(IllegalArgumentException.class, () -> converter.importRequest2spec(request));
    }

    @Test
    void mapsSchemaImportSourcesDependenciesAndExecutionOptions() {
        TaskImportRequest request = new TaskImportRequest();
        request.setTaskType(TaskType.DATA_FILE_IMPORT.name());
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setScope("schema");
        request.setSourceKind("third_party");
        request.setCycleStrategy("staging_two_phase");
        request.setStagingPolicy(ImportStagingPolicy.builder()
                .enabled(true).allVarchar(true).twoPhase(true).build());
        request.setValidationOptions(ImportValidationOptions.builder()
                .sourceProfiling(true).orphanCheck(true).build());
        request.setPerformanceSamplePercent(5);
        request.setTableSources(List.of(tableSource("orders", "orders-file"),
                tableSource("order_items", "items-file")));
        request.setLogicalDependencies(List.of(ImportTableDependency.builder()
                .parentTable("orders").parentColumn("id")
                .childTable("order_items").childColumn("order_id").logical(true).build()));

        ImportTaskSpec spec = converter.importRequest2spec(request);

        assertEquals("SCHEMA", spec.getScope());
        assertEquals("Import schema data - app.public.orders, order_items", spec.getTaskName());
        assertEquals(2, spec.getTableSources().size());
        assertEquals("app", spec.getTableSources().get(0).getDatabaseName());
        assertEquals("public", spec.getTableSources().get(0).getSchemaName());
        assertEquals("orders-file", spec.getTableSources().get(0).getImportFileId());
        assertEquals("CSV", spec.getTableSources().get(0).getFormat());
        assertEquals(1, spec.getLogicalDependencies().size());
        assertEquals("THIRD_PARTY", spec.getSourceKind());
        assertEquals("STAGING_TWO_PHASE", spec.getCycleStrategy());
        assertEquals(Boolean.TRUE, spec.getStagingPolicy().getAllVarchar());
        assertEquals(Boolean.TRUE, spec.getValidationOptions().getOrphanCheck());
        assertEquals(5, spec.getPerformanceSamplePercent());
    }

    @Test
    void rejectsNullImportTableSourceWithParameterError() {
        TaskImportRequest request = importRequest(TaskType.DATA_FILE_IMPORT.name());
        request.setTableSources(Collections.singletonList(null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> converter.importRequest2spec(request));

        assertEquals("Import table source cannot be null", error.getMessage());
    }

    @Test
    void rejectsEveryMultiTableManifestControlOnSqlFileRequests() {
        List<Consumer<TaskImportRequest>> invalidControls = List.of(
                request -> request.setTableSources(List.of(tableSource("orders", "orders-file"))),
                request -> request.setLogicalDependencies(List.of(ImportTableDependency.builder()
                        .parentTable("orders").childTable("items").build())),
                request -> request.setCycleStrategy("REJECT"),
                request -> request.setStagingPolicy(ImportStagingPolicy.builder().enabled(false).build()),
                request -> request.setValidationOptions(ImportValidationOptions.builder().orphanCheck(false).build()),
                request -> request.setFinalizationOptions(ImportFinalizationOptions.builder()
                        .resetSequences(false).build()),
                request -> request.setRollbackOptions(ImportRollbackOptions.builder().rehearsal(false).build()),
                request -> request.setPerformanceSamplePercent(5),
                request -> request.setConfirmedNoStrongRelations(false));

        for (Consumer<TaskImportRequest> invalidControl : invalidControls) {
            TaskImportRequest request = importRequest(TaskType.SQL_FILE_IMPORT.name());
            request.setScope("DATABASE");
            request.setSourceKind("THIRD_PARTY");
            request.setMode("STANDARD");
            invalidControl.accept(request);

            assertEquals("SQL file import does not accept multi-table manifest controls",
                    assertThrows(IllegalArgumentException.class,
                            () -> converter.importRequest2spec(request)).getMessage());
        }
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

    private TaskImportTableSourceRequest tableSource(String tableName, String fileId) {
        TaskImportTableSourceRequest source = new TaskImportTableSourceRequest();
        source.setTableName(tableName);
        source.setFileId(fileId);
        source.setFormat("csv");
        return source;
    }
}
