package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.file.IImportFileRegistry;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataFileImportTaskExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void mappedCsvTasksUseThePreviewParserExecutionPath() throws Exception {
        Path sourceFile = Files.writeString(tempDir.resolve("orders.csv"), "amount\n42\n");
        AtomicReference<Map<String, Object>> capturedCsvOptions = new AtomicReference<>();
        AtomicReference<List<Map<String, String>>> capturedMappings = new AtomicReference<>();
        AtomicReference<String> capturedStrategy = new AtomicReference<>();
        AtomicReference<String> releasedFileId = new AtomicReference<>();
        AtomicReference<Map<String, Object>> summary = new AtomicReference<>();
        IDbImportPreviewService service = new IDbImportPreviewService() {
            @Override
            public Map<String, Object> preview(Long dataSourceId, String databaseName, String schemaName,
                    String tableName, File file, Map<String, Object> csvOptions) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> execute(Long dataSourceId, String databaseName, String schemaName,
                    String tableName, File file, Map<String, Object> csvOptions,
                    List<Map<String, String>> mappings, String unmappedTarget, TaskExecutionContext context) {
                capturedCsvOptions.set(csvOptions);
                capturedMappings.set(mappings);
                capturedStrategy.set(unmappedTarget);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("totalRows", 2);
                result.put("successCount", 1);
                result.put("failedCount", 1);
                result.put("errors", List.of(Map.of("row", 3, "column", "amount", "message", "invalid numeric")));
                return result;
            }
        };
        IImportFileRegistry importFileRegistry = (IImportFileRegistry) Proxy.newProxyInstance(
                DataFileImportTaskExecutorTest.class.getClassLoader(), new Class<?>[]{IImportFileRegistry.class},
                (proxy, method, args) -> {
                    if ("release".equals(method.getName())) {
                        releasedFileId.set((String) args[0]);
                    }
                    return null;
                });
        DataFileImportTaskExecutor executor = new DataFileImportTaskExecutor(service, importFileRegistry);
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .format(TaskFileFormat.CSV.name())
                .sourceFile(sourceFile.toString())
                .importFileId("file-1")
                .target(TaskTargetSnapshot.builder()
                        .dataSourceId(7L)
                        .databaseName("shop")
                        .schemaName("public")
                        .tableName("orders")
                        .build())
                .csvOptions(CsvOptions.builder()
                        .encoding("AUTO")
                        .delimiter("|")
                        .quote("\"")
                        .escape("\\")
                        .newline("LF")
                        .hasHeader(true)
                        .emptyAsNull(true)
                        .build())
                .mappings(List.of(Map.of("sourceColumn", "amount", "targetColumn", "amount")))
                .unmappedTarget("NULL")
                .build();

        executor.execute(spec, context(summary));

        assertEquals("|", capturedCsvOptions.get().get("delimiter"));
        assertEquals(List.of(Map.of("sourceColumn", "amount", "targetColumn", "amount")),
                capturedMappings.get());
        assertEquals("NULL", capturedStrategy.get());
        assertEquals("file-1", releasedFileId.get());
        assertEquals(0, summary.get().get("skippedCount"));
    }

    private static TaskExecutionContext context(AtomicReference<Map<String, Object>> summary) {
        return (TaskExecutionContext) Proxy.newProxyInstance(DataFileImportTaskExecutorTest.class.getClassLoader(),
                new Class<?>[]{TaskExecutionContext.class}, (proxy, method, args) -> {
                    if ("logInfo".equals(method.getName()) && args != null && args.length == 3) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> details = (Map<String, Object>) args[2];
                        summary.set(details);
                    }
                    return null;
                });
    }

}
