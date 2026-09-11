package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.model.db.ImportTargetColumn;
import ai.chat2db.community.domain.api.model.db.MappedImportExecution;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.IImportTaskSubmissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbMappedImportServiceImplTest {

    @Test
    void submitsValidatedMappingWithCanonicalTarget(@TempDir Path directory) throws Exception {
        RecordingStagingService stagingService = stagingService(directory);
        AtomicReference<ImportTaskSpec> submitted = new AtomicReference<>();
        DbMappedImportServiceImpl service = service(stagingService, preview("Orders", target("name")), submitted);

        assertEquals(42L, service.submit(execution(mapping("Name", "name"))));

        ImportTaskSpec spec = submitted.get();
        assertEquals("Orders", spec.getTarget().getTableName());
        assertEquals(stagingService.file.getAbsolutePath(), spec.getSourceFile());
        assertEquals("file-id", spec.getImportFileId());
    }

    @Test
    void rejectsDuplicateTargetsBeforeClaimingFile(@TempDir Path directory) throws Exception {
        RecordingStagingService stagingService = stagingService(directory);
        DbMappedImportServiceImpl service = service(stagingService, preview("orders", target("name")),
                new AtomicReference<>());

        assertThrows(IllegalArgumentException.class, () -> service.submit(execution(List.of(
                mapping("name", "name"), mapping("email", "NAME")))));

    }

    @Test
    void rejectsMappingThatIsNotInCurrentPreview(@TempDir Path directory) throws Exception {
        RecordingStagingService stagingService = stagingService(directory);
        DbMappedImportServiceImpl service = service(stagingService, preview("orders", target("name")),
                new AtomicReference<>());

        assertThrows(IllegalArgumentException.class,
                () -> service.submit(execution(mapping("missing", "name"))));

    }

    @Test
    void rejectsMissingRequiredTargetBeforeClaimingFile(@TempDir Path directory) throws Exception {
        RecordingStagingService stagingService = stagingService(directory);
        ImportTargetColumn required = ImportTargetColumn.builder().name("required_code").build();
        DbMappedImportServiceImpl service = service(stagingService,
                preview("orders", target("name"), required), new AtomicReference<>());
        MappedImportExecution execution = execution(mapping("Name", "name"));
        execution.setUnmappedTarget(UnmappedTargetStrategy.DEFAULT);

        assertThrows(IllegalArgumentException.class, () -> service.submit(execution));

    }

    private static DbMappedImportServiceImpl service(RecordingStagingService stagingService,
            IDbImportPreviewService previewService, AtomicReference<ImportTaskSpec> submitted) {
        IImportTaskSubmissionService submissionService = (spec, stagedFileId) -> {
            submitted.set(spec);
            assertEquals("file-id", stagedFileId);
            return 42L;
        };
        return new DbMappedImportServiceImpl(previewService, stagingService, submissionService);
    }

    private static IDbImportPreviewService preview(String tableName, ImportTargetColumn... targetColumns) {
        return (dataSourceId, databaseName, schemaName, requestedTableName, file) -> ImportPreview.builder()
                .sourceColumns(List.of("Name", "email"))
                .targetTableName(tableName)
                .targetColumns(List.of(targetColumns))
                .build();
    }

    private static MappedImportExecution execution(ImportColumnMapping... mappings) {
        return execution(List.of(mappings));
    }

    private static MappedImportExecution execution(List<ImportColumnMapping> mappings) {
        return MappedImportExecution.builder()
                .dataSourceId(7L)
                .databaseName("app")
                .schemaName("public")
                .tableName("orders")
                .fileId("file-id")
                .mappings(mappings)
                .build();
    }

    private static ImportColumnMapping mapping(String source, String target) {
        return ImportColumnMapping.builder().sourceColumn(source).targetColumn(target).build();
    }

    private static ImportTargetColumn target(String name) {
        return ImportTargetColumn.builder().name(name).nullable(true).build();
    }

    private static RecordingStagingService stagingService(Path directory) throws Exception {
        return new RecordingStagingService(Files.writeString(directory.resolve("file-id.csv"), "Name\nAda\n").toFile());
    }

    private static final class RecordingStagingService implements IImportFileStagingService {

        private final File file;
        private RecordingStagingService(File file) {
            this.file = file;
        }

        @Override
        public String stage(File source, String originalFileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File resolve(String fileId) {
            return file;
        }

        @Override
        public void claimForTask(String fileId) {
        }

        @Override
        public void release(String fileId) {
        }
    }
}
