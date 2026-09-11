package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.web.api.config.console.DesktopBridgeRequestContext;
import ai.chat2db.community.web.api.model.request.db.DesktopImportFileRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportFileUploadAdapterTest {

    @Test
    void stagesDesktopFileOnlyInsideJcefBridge(@TempDir Path directory) throws Exception {
        File selectedFile = Files.writeString(directory.resolve("users.csv"), "name\nAda\n").toFile();
        RecordingStagingService stagingService = new RecordingStagingService();
        ImportFileUploadAdapter adapter = new ImportFileUploadAdapter(null, stagingService);
        DesktopImportFileRequest request = new DesktopImportFileRequest();
        request.setSourceFile(selectedFile.getAbsolutePath());
        request.setOriginalFileName("users.csv");

        assertThrows(BusinessException.class, () -> adapter.stageDesktopFile(request));

        String fileId = DesktopBridgeRequestContext.invoke(() -> adapter.stageDesktopFile(request));
        assertEquals("file-id", fileId);
        assertEquals(selectedFile, stagingService.sourceFile);
        assertEquals("users.csv", stagingService.originalFileName);
    }

    private static final class RecordingStagingService implements IImportFileStagingService {

        private File sourceFile;
        private String originalFileName;

        @Override
        public String stage(File file, String fileName) {
            sourceFile = file;
            originalFileName = fileName;
            return "file-id";
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
            throw new UnsupportedOperationException();
        }
    }
}
