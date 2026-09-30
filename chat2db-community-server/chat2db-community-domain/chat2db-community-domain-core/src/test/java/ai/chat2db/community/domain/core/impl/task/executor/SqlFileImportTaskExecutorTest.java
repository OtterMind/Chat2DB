package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFileImportTaskExecutorTest {

    @Test
    void releasesStagedFileAndResumeStateAfterExecutionFailureBecomesTerminal(@TempDir Path tempDirectory) throws Exception {
        File source = Files.writeString(tempDirectory.resolve("input.sql"), "select 1").toFile();
        RecordingImportFileStagingService stagingService = new RecordingImportFileStagingService();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(source.getAbsolutePath())
                .format("JSON")
                .importFileId("staged-file-id")
                .build();

        AtomicReference<Long> clearedTaskId = new AtomicReference<>();
        SqlFileImportTaskExecutor executor =
                new SqlFileImportTaskExecutor(stagingService, recordingTaskStorage(clearedTaskId));
        assertThrows(TaskExecutionException.class, () -> executor.execute(spec, null));
        executor.cleanupTerminalResources(spec, 1L);

        assertEquals("staged-file-id", stagingService.releasedFileId);
        assertEquals(1L, clearedTaskId.get());
    }

    @Test
    void terminalCleanupWithoutTaskIdStillReleasesTheStagedFile(@TempDir Path tempDirectory) throws Exception {
        File source = Files.writeString(tempDirectory.resolve("input.sql"), "select 1").toFile();
        RecordingImportFileStagingService stagingService = new RecordingImportFileStagingService();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(source.getAbsolutePath())
                .format("JSON")
                .importFileId("staged-file-id")
                .build();

        AtomicReference<Long> clearedTaskId = new AtomicReference<>();
        SqlFileImportTaskExecutor executor =
                new SqlFileImportTaskExecutor(stagingService, recordingTaskStorage(clearedTaskId));
        executor.cleanupTerminalResources(spec, null);

        assertEquals("staged-file-id", stagingService.releasedFileId);
        assertNull(clearedTaskId.get());
    }

    @Test
    void releaseFailureIsRethrownAfterResumeStateIsCleared(@TempDir Path tempDirectory) throws Exception {
        File source = Files.writeString(tempDirectory.resolve("input.sql"), "select 1").toFile();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(source.getAbsolutePath())
                .format("JSON")
                .importFileId("staged-file-id")
                .build();

        AtomicReference<Long> clearedTaskId = new AtomicReference<>();
        IImportFileStagingService failingStagingService = new RecordingImportFileStagingService() {
            @Override
            public void release(String fileId) {
                throw new IllegalStateException("staging directory is gone");
            }
        };
        SqlFileImportTaskExecutor executor =
                new SqlFileImportTaskExecutor(failingStagingService, recordingTaskStorage(clearedTaskId));

        assertThrows(IllegalStateException.class, () -> executor.cleanupTerminalResources(spec, 7L));
        assertEquals(7L, clearedTaskId.get());
    }

    private static TaskStorage recordingTaskStorage(AtomicReference<Long> clearedTaskId) {
        return (TaskStorage) Proxy.newProxyInstance(
                TaskStorage.class.getClassLoader(),
                new Class<?>[]{TaskStorage.class},
                (proxy, method, args) -> {
                    if ("clearResumeStates".equals(method.getName())) {
                        clearedTaskId.set((Long) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static class RecordingImportFileStagingService implements IImportFileStagingService {

        private String releasedFileId;

        @Override
        public String stage(File file, String originalFileName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File resolve(String fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void claimForTask(String fileId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(String fileId) {
            releasedFileId = fileId;
        }
    }
}
