package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MultipartJdbcDriverUploadAdapterTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void uploadCreatesDirectoryUnderTheCurrentUserHome() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalRuntimeMode = System.getProperty("chat2db.runtime.mode");
        byte[] driverBytes = "uploaded-driver".getBytes(StandardCharsets.UTF_8);
        Path initializedHome = temporaryDirectory.resolve("initialized-home");
        Path activeHome = temporaryDirectory.resolve("active-home");
        try {
            System.setProperty("chat2db.runtime.mode", "community");
            System.setProperty("user.home", initializedHome.toString());
            JdbcDriverConstants.getDriverLibPath();
            MultipartJdbcDriverUploadAdapter adapter = new MultipartJdbcDriverUploadAdapter();

            System.setProperty("user.home", activeHome.toString());
            Path target = activeHome.resolve(".chat2db-community").resolve("jdbc-lib")
                    .resolve("uploaded-driver.jar");
            assertFalse(Files.exists(target.getParent()));

            assertEquals(List.of("uploaded-driver.jar"),
                    adapter.upload(new MultipartFile[]{multipartFile("uploaded-driver.jar", driverBytes)}));
            assertArrayEquals(driverBytes, Files.readAllBytes(target));
        } finally {
            restoreProperty("user.home", originalHome);
            restoreProperty("chat2db.runtime.mode", originalRuntimeMode);
        }
    }

    private MultipartFile multipartFile(String fileName, byte[] contents) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return fileName;
            }

            @Override
            public String getOriginalFilename() {
                return fileName;
            }

            @Override
            public String getContentType() {
                return "application/java-archive";
            }

            @Override
            public boolean isEmpty() {
                return contents.length == 0;
            }

            @Override
            public long getSize() {
                return contents.length;
            }

            @Override
            public byte[] getBytes() {
                return contents.clone();
            }

            @Override
            public InputStream getInputStream() {
                return new java.io.ByteArrayInputStream(contents);
            }

            @Override
            public void transferTo(File destination) throws IOException {
                Files.write(destination.toPath(), contents);
            }
        };
    }

    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
