package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportTaskSubmissionServiceImplTest {

    @Test
    void retryReturnsPersistedTaskBeforeTouchingAlreadyClaimedStagedFiles() {
        RecordingStagingService stagingService = new RecordingStagingService(null);
        AtomicReference<ImportTaskSpec> submitted = new AtomicReference<>();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .clientSubmissionId("import-attempt-1")
                .importFileId("claimed-file-id")
                .build();

        assertEquals(73L, service(stagingService, submitted, null, 73L).submit(spec, "claimed-file-id"));

        assertFalse(stagingService.resolved);
        assertFalse(stagingService.claimed);
        assertNull(submitted.get());
        assertEquals(64, spec.getClientSubmissionFingerprint().length());
    }

    @Test
    void canonicalFingerprintIgnoresResolvedPathsAndDetectsSemanticChanges() {
        ImportTaskSpec original = ImportTaskSpec.builder()
                .clientSubmissionId("attempt-1")
                .taskType("DATA_FILE_IMPORT")
                .importFileId("staged-users")
                .sourceFile("C:/untrusted/users.csv")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("users").build())
                .options(ImportOptions.builder().delimiter(",").skipRows(1).build())
                .build();
        ImportTaskSpec sameRequestAfterResolution = ImportTaskSpec.builder()
                .clientSubmissionId("another-key-is-ignored")
                .clientSubmissionFingerprint("server-field-is-ignored")
                .taskType("DATA_FILE_IMPORT")
                .importFileId("staged-users")
                .sourceFile("D:/server/staging/staged-users.csv")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("users").build())
                .options(ImportOptions.builder().delimiter(",").skipRows(1).build())
                .build();

        String fingerprint = ImportTaskSubmissionFingerprint.create(original, "staged-users");

        assertEquals(fingerprint,
                ImportTaskSubmissionFingerprint.create(sameRequestAfterResolution, "staged-users"));
        assertNotEquals(fingerprint, ImportTaskSubmissionFingerprint.create(
                ImportTaskSpec.builder()
                        .taskType("DATA_FILE_IMPORT")
                        .importFileId("staged-replacement")
                        .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("users").build())
                        .options(ImportOptions.builder().delimiter(",").skipRows(1).build())
                        .build(), "staged-replacement"));
        assertNotEquals(fingerprint, ImportTaskSubmissionFingerprint.create(
                ImportTaskSpec.builder()
                        .taskType("DATA_FILE_IMPORT")
                        .importFileId("staged-users")
                        .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("archived_users").build())
                        .options(ImportOptions.builder().delimiter(",").skipRows(1).build())
                        .build(), "staged-users"));
        assertNotEquals(fingerprint, ImportTaskSubmissionFingerprint.create(
                ImportTaskSpec.builder()
                        .taskType("DATA_FILE_IMPORT")
                        .importFileId("staged-users")
                        .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("users").build())
                        .options(ImportOptions.builder().delimiter(";").skipRows(1).build())
                        .build(), "staged-users"));
    }

    @Test
    void preservesDesktopSourceWhenNoStagedFileIdIsPresent() {
        RecordingStagingService stagingService = new RecordingStagingService(null);
        AtomicReference<ImportTaskSpec> submitted = new AtomicReference<>();
        ImportTaskSubmissionServiceImpl service = service(stagingService, submitted, null);
        ImportTaskSpec spec = ImportTaskSpec.builder().sourceFile("/desktop/users.json").build();

        assertEquals(42L, service.submit(spec, null));

        assertEquals("/desktop/users.json", submitted.get().getSourceFile());
        assertFalse(stagingService.resolved);
        assertFalse(stagingService.claimed);
    }

    @Test
    void resolvesAndClaimsServerStagedFile(@TempDir Path directory) throws Exception {
        File stagedFile = Files.writeString(directory.resolve("file-id.json"), "[]").toFile();
        RecordingStagingService stagingService = new RecordingStagingService(stagedFile);
        AtomicReference<ImportTaskSpec> submitted = new AtomicReference<>();
        ImportTaskSubmissionServiceImpl service = service(stagingService, submitted, null);

        assertEquals(42L, service.submit(new ImportTaskSpec(), "file-id"));

        assertTrue(stagingService.resolved);
        assertTrue(stagingService.claimed);
        assertFalse(stagingService.released);
        assertEquals(stagedFile.getAbsolutePath(), submitted.get().getSourceFile());
        assertEquals("file-id", submitted.get().getImportFileId());
    }

    @Test
    void rejectsConflictingTopLevelStagedFileIdsBeforeResolvingEither() {
        RecordingStagingService stagingService = new RecordingStagingService(null);
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .clientSubmissionId("import-attempt-1")
                .importFileId("spec-file-id")
                .build();

        assertEquals("Top-level staged import file IDs do not match",
                assertThrows(IllegalArgumentException.class,
                        () -> service(stagingService, new AtomicReference<>(), null)
                                .submit(spec, "argument-file-id"))
                        .getMessage());
        assertFalse(stagingService.resolved);
        assertFalse(stagingService.claimed);
    }

    @Test
    void releasesClaimedFileWhenTaskSubmissionFails(@TempDir Path directory) throws Exception {
        File stagedFile = Files.writeString(directory.resolve("file-id.sql"), "select 1").toFile();
        RecordingStagingService stagingService = new RecordingStagingService(stagedFile);
        ImportTaskSubmissionServiceImpl service = service(stagingService, new AtomicReference<>(),
                new IllegalStateException("failed"));

        assertThrows(IllegalStateException.class,
                () -> service.submit(new ImportTaskSpec(), "file-id"));

        assertTrue(stagingService.claimed);
        assertTrue(stagingService.released);
    }

    @Test
    void claimsEveryTableSourceAndResolvesItsServerPath(@TempDir Path directory) throws Exception {
        File orders = Files.writeString(directory.resolve("orders.csv"), "id\n1\n").toFile();
        File items = Files.writeString(directory.resolve("items.csv"), "id\n2\n").toFile();
        MultiRecordingStagingService staging = new MultiRecordingStagingService(
                Map.of("orders-id", orders, "items-id", items));
        AtomicReference<ImportTaskSpec> submitted = new AtomicReference<>();
        ImportTaskSubmissionServiceImpl service = service(staging, submitted, null);
        ImportTaskSpec spec = ImportTaskSpec.builder().tableSources(List.of(
                ImportTableSource.builder().tableName("orders").importFileId("orders-id").build(),
                ImportTableSource.builder().tableName("items").importFileId("items-id").build())).build();

        assertEquals(42L, service.submit(spec, null));

        assertEquals(List.of("orders-id", "items-id"), staging.claimedIds);
        assertEquals(orders.getAbsolutePath(), submitted.get().getTableSources().get(0).getSourceFile());
        assertEquals(items.getAbsolutePath(), submitted.get().getTableSources().get(1).getSourceFile());
        assertTrue(staging.releasedIds.isEmpty());
    }

    @Test
    void alwaysClaimsAndResolvesTheTopLevelFileIdBeforeTableSourceIds(@TempDir Path directory) throws Exception {
        File topLevel = Files.writeString(directory.resolve("top-level.sql"), "SELECT 1;").toFile();
        File child = Files.writeString(directory.resolve("child.sql"), "SELECT 2;").toFile();
        MultiRecordingStagingService staging = new MultiRecordingStagingService(
                Map.of("top-level-id", topLevel, "child-id", child));
        AtomicReference<ImportTaskSpec> submitted = new AtomicReference<>();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .importFileId("top-level-id")
                .sourceFile("untrusted-client-path.sql")
                .tableSources(List.of(ImportTableSource.builder()
                        .tableName("orders").importFileId("child-id").build()))
                .build();

        assertEquals(42L, service(staging, submitted, null).submit(spec, null));

        assertEquals(List.of("top-level-id", "child-id"), staging.claimedIds);
        assertEquals(topLevel.getAbsolutePath(), submitted.get().getSourceFile());
        assertEquals(child.getAbsolutePath(), submitted.get().getTableSources().get(0).getSourceFile());
    }

    @Test
    void releasesAllTableSourceClaimsWhenSubmissionFails(@TempDir Path directory) throws Exception {
        MultiRecordingStagingService staging = new MultiRecordingStagingService(new LinkedHashMap<>(Map.of(
                "orders-id", Files.writeString(directory.resolve("orders.csv"), "id\n1\n").toFile(),
                "items-id", Files.writeString(directory.resolve("items.csv"), "id\n2\n").toFile())));
        ImportTaskSpec spec = ImportTaskSpec.builder().tableSources(List.of(
                ImportTableSource.builder().tableName("orders").importFileId("orders-id").build(),
                ImportTableSource.builder().tableName("items").importFileId("items-id").build())).build();

        assertThrows(IllegalStateException.class, () -> service(staging, new AtomicReference<>(),
                new IllegalStateException("failed")).submit(spec, null));

        assertEquals(List.of("orders-id", "items-id"), staging.claimedIds);
        assertEquals(List.of("items-id", "orders-id"), staging.releasedIds);
    }

    private static ImportTaskSubmissionServiceImpl service(IImportFileStagingService stagingService,
            AtomicReference<ImportTaskSpec> submitted, RuntimeException failure) {
        return service(stagingService, submitted, failure, null);
    }

    private static ImportTaskSubmissionServiceImpl service(IImportFileStagingService stagingService,
            AtomicReference<ImportTaskSpec> submitted, RuntimeException failure, Long existingTaskId) {
        TaskService taskService = (TaskService) Proxy.newProxyInstance(
                ImportTaskSubmissionServiceImplTest.class.getClassLoader(), new Class<?>[] {TaskService.class},
                (proxy, method, arguments) -> {
                    if ("findImportTaskId".equals(method.getName())) {
                        assertEquals("import-attempt-1", arguments[0]);
                        assertEquals(64, ((String) arguments[1]).length());
                        return existingTaskId;
                    }
                    if ("submitImport".equals(method.getName())) {
                        submitted.set((ImportTaskSpec) arguments[0]);
                        if (failure != null) {
                            throw failure;
                        }
                        return 42L;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new ImportTaskSubmissionServiceImpl(taskService, stagingService);
    }

    private static final class RecordingStagingService implements IImportFileStagingService {

        private final File file;
        private boolean resolved;
        private boolean claimed;
        private boolean released;

        private RecordingStagingService(File file) {
            this.file = file;
        }

        @Override
        public String stage(File source, String originalFileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File resolve(String fileId) {
            resolved = true;
            return file;
        }

        @Override
        public void claimForTask(String fileId) {
            claimed = true;
        }

        @Override
        public void release(String fileId) {
            released = true;
        }
    }

    private static final class MultiRecordingStagingService implements IImportFileStagingService {
        private final Map<String, File> files;
        private final List<String> claimedIds = new ArrayList<>();
        private final List<String> releasedIds = new ArrayList<>();

        private MultiRecordingStagingService(Map<String, File> files) {
            this.files = files;
        }

        @Override public String stage(File source, String originalFileName) { throw new UnsupportedOperationException(); }
        @Override public File resolve(String fileId) { return files.get(fileId); }
        @Override public void claimForTask(String fileId) { claimedIds.add(fileId); }
        @Override public void release(String fileId) { releasedIds.add(fileId); }
    }
}
