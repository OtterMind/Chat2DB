package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.tools.util.JdbcJarUtils;
import ai.chat2db.community.web.api.config.console.MyMultipartFile;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartJdbcDriverUploadAdapterTest {

    @Test
    void uploadStoresDriverArtifactsInsideJdbcLib() throws Exception {
        MultipartJdbcDriverUploadAdapter adapter = new MultipartJdbcDriverUploadAdapter();
        String fileName = uniqueFileName(".jar");
        File output = JdbcJarUtils.driverFile(fileName);
        output.delete();

        try {
            List<String> uploaded = adapter.upload(new MyMultipartFile[] {
                    new MyMultipartFile("file", fileName, "application/java-archive", new byte[] {1, 2, 3})
            });

            assertEquals(List.of(fileName), uploaded);
            assertTrue(output.exists());
        } finally {
            output.delete();
        }
    }

    @Test
    void uploadRejectsNonDriverArtifacts() throws Exception {
        MultipartJdbcDriverUploadAdapter adapter = new MultipartJdbcDriverUploadAdapter();
        String fileName = uniqueFileName(".txt");

        assertThrows(IOException.class, () -> adapter.upload(new MyMultipartFile[] {
                new MyMultipartFile("file", fileName, "text/plain", new byte[] {1})
        }));
        assertFalse(new File(JdbcJarUtils.driverFile(fileName.replace(".txt", ".jar")).getParentFile(), fileName).exists());
    }

    private String uniqueFileName(String suffix) {
        return "jdbc-driver-upload-test-" + UUID.randomUUID() + suffix;
    }
}
