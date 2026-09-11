package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportTaskSubmissionServiceImplTest {

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

    private static ImportTaskSubmissionServiceImpl service(RecordingStagingService stagingService,
            AtomicReference<ImportTaskSpec> submitted, RuntimeException failure) {
        TaskService taskService = (TaskService) Proxy.newProxyInstance(
                ImportTaskSubmissionServiceImplTest.class.getClassLoader(), new Class<?>[] {TaskService.class},
                (proxy, method, arguments) -> {
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
}
