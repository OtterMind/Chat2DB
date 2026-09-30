package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.model.request.db.ImportFileReleaseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbImportPreviewControllerTest {

    @Test
    void releasesOnlyThroughTheUnclaimedStagingOperation() throws Exception {
        AtomicReference<String> releasedFileId = new AtomicReference<>();
        IImportFileStagingService stagingService = new IImportFileStagingService() {
            @Override public String stage(File file, String originalFileName) { throw new AssertionError(); }
            @Override public File resolve(String fileId) { throw new AssertionError(); }
            @Override public void claimForTask(String fileId) { throw new AssertionError(); }
            @Override public boolean releaseUnclaimed(String fileId) {
                releasedFileId.set(fileId);
                return true;
            }
            @Override public void release(String fileId) { throw new AssertionError(); }
        };
        DbImportPreviewController controller = new DbImportPreviewController(
                null, null, stagingService, null, null);
        ImportFileReleaseRequest request = new ImportFileReleaseRequest();
        request.setFileId("preview-file-id");

        DataResult<Void> result = controller.release(request);

        assertEquals("preview-file-id", releasedFileId.get());
        assertTrue(result.getSuccess());
        assertNull(result.getData());
        PostMapping mapping = DbImportPreviewController.class
                .getDeclaredMethod("release", ImportFileReleaseRequest.class)
                .getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/release"}, mapping.value());
    }
}
