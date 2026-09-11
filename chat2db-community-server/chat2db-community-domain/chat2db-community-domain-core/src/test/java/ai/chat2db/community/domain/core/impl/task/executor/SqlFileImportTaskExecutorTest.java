package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFileImportTaskExecutorTest {

    @Test
    void releasesStagedFileWhenExecutionFails(@TempDir Path tempDirectory) throws Exception {
        File source = Files.writeString(tempDirectory.resolve("input.sql"), "select 1").toFile();
        RecordingImportFileStagingService stagingService = new RecordingImportFileStagingService();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(source.getAbsolutePath())
                .format("JSON")
                .importFileId("staged-file-id")
                .build();

        assertThrows(TaskExecutionException.class,
                () -> new SqlFileImportTaskExecutor(stagingService).execute(spec, null));

        assertEquals("staged-file-id", stagingService.releasedFileId);
    }

    private static final class RecordingImportFileStagingService implements IImportFileStagingService {

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
