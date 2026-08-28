package ai.chat2db.community.web.api.adapter.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartJdbcDriverUploadAdapterTest {

    @TempDir
    Path driverDirectory;

    @Test
    void stagesJarUnderOpaqueToken() throws Exception {
        List<String> uploadTokens = MultipartJdbcDriverUploadAdapter.store(
                new MultipartFile[]{file("../mysql-test.jar", "driver")}, driverDirectory);

        assertEquals(1, uploadTokens.size());
        String[] tokenParts = uploadTokens.get(0).split(":", 2);
        assertEquals("mysql-test.jar", tokenParts[1]);
        assertEquals("driver", Files.readString(driverDirectory.resolve(tokenParts[0] + ".upload")));
    }

    @Test
    void rejectsEmptyNullNameNonJarAndOversizedUploadsWithoutResidue() throws Exception {
        assertThrows(IOException.class,
                () -> MultipartJdbcDriverUploadAdapter.store(new MultipartFile[0], driverDirectory));
        assertThrows(IOException.class,
                () -> MultipartJdbcDriverUploadAdapter.store(
                        new MultipartFile[]{file("driver.txt", "not a jar")}, driverDirectory));
        assertThrows(IOException.class,
                () -> MultipartJdbcDriverUploadAdapter.store(
                        new MultipartFile[]{file(null, "driver")}, driverDirectory));
        assertThrows(IOException.class,
                () -> MultipartJdbcDriverUploadAdapter.store(
                        new MultipartFile[]{file("driver.jar", "too large")}, driverDirectory, 4));
        assertThrows(IOException.class,
                () -> MultipartJdbcDriverUploadAdapter.store(
                        new MultipartFile[]{file("first.jar", "driver"), file("second.jar", "driver")},
                        driverDirectory));
        try (var files = Files.list(driverDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void cleansOnlyExpiredManagedStagingFiles() throws Exception {
        String uploadId = "0123456789abcdef0123456789abcdef";
        Path expiredUpload = Files.writeString(driverDirectory.resolve(uploadId + ".upload"), "driver");
        Path expiredTemporary = Files.writeString(driverDirectory.resolve(".driver-upload-old.tmp"), "driver");
        Path freshUpload = Files.writeString(
                driverDirectory.resolve("fedcba9876543210fedcba9876543210.upload"), "driver");
        Path unrelated = Files.writeString(driverDirectory.resolve("keep.txt"), "keep");
        Instant cutoff = Instant.now().minusSeconds(60);
        FileTime expired = FileTime.from(cutoff.minusSeconds(1));
        Files.setLastModifiedTime(expiredUpload, expired);
        Files.setLastModifiedTime(expiredTemporary, expired);

        assertEquals(2, MultipartJdbcDriverUploadAdapter.cleanupExpiredUploads(driverDirectory, cutoff));
        try (var remaining = Files.list(driverDirectory)) {
            assertEquals(List.of(freshUpload, unrelated).stream().sorted().toList(),
                    remaining.sorted().toList());
        }
    }

    private MultipartFile file(String originalFilename, String contents) {
        byte[] bytes = contents.getBytes();
        return new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return originalFilename;
            }

            @Override
            public String getContentType() {
                return "application/java-archive";
            }

            @Override
            public boolean isEmpty() {
                return bytes.length == 0;
            }

            @Override
            public long getSize() {
                return bytes.length;
            }

            @Override
            public byte[] getBytes() {
                return bytes.clone();
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public void transferTo(File dest) throws IOException {
                Files.write(dest.toPath(), bytes);
            }
        };
    }
}
