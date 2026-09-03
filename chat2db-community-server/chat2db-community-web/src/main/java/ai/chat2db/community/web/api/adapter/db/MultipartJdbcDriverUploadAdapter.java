package ai.chat2db.community.web.api.adapter.db;

import ai.chat2db.community.domain.api.service.db.IDbJdbcDriverUploadService;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class MultipartJdbcDriverUploadAdapter implements IDbJdbcDriverUploadService<MultipartFile[]> {

    static final long MAX_DRIVER_FILE_SIZE_BYTES = 100L * 1024L * 1024L;
    static final Duration STAGED_UPLOAD_RETENTION = Duration.ofHours(24);

    @Override
    public List<String> upload(MultipartFile[] files) throws IOException {
        cleanupExpiredUploads();
        return store(files, Path.of(JdbcDriverConstants.DRIVER_UPLOAD_PATH), MAX_DRIVER_FILE_SIZE_BYTES);
    }

    @PostConstruct
    void cleanupExpiredUploadsOnStart() {
        cleanupExpiredUploads();
    }

    @Scheduled(fixedDelay = 60L * 60L * 1000L)
    void cleanupExpiredUploads() {
        try {
            cleanupExpiredUploads(
                    Path.of(JdbcDriverConstants.DRIVER_UPLOAD_PATH),
                    Instant.now().minus(STAGED_UPLOAD_RETENTION));
        } catch (IOException exception) {
            log.warn("Unable to clean expired JDBC driver uploads", exception);
        }
    }

    static List<String> store(MultipartFile[] files, Path stagingDirectory) throws IOException {
        return store(files, stagingDirectory, MAX_DRIVER_FILE_SIZE_BYTES);
    }

    static List<String> store(MultipartFile[] files, Path stagingDirectory, long maxFileSizeBytes) throws IOException {
        if (files == null || files.length != 1) {
            throw new IOException("Exactly one JDBC driver file must be uploaded");
        }
        List<String> originalFilenames = new ArrayList<>();
        for (MultipartFile file : files) {
            String originalFilename = FilenameUtils.getName(file.getOriginalFilename());
            if (file.isEmpty() || originalFilename == null || originalFilename.isBlank()
                    || originalFilename.indexOf(',') >= 0
                    || !"jar".equalsIgnoreCase(FilenameUtils.getExtension(originalFilename))) {
                throw new IOException("Only non-empty JDBC driver JAR files can be uploaded");
            }
            if (file.getSize() > maxFileSizeBytes) {
                throw new IOException("JDBC driver file exceeds the size limit");
            }
            originalFilenames.add(originalFilename);
        }

        Path normalizedDirectory = stagingDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDirectory);
        List<Path> stagedFiles = new ArrayList<>();
        List<String> uploadTokens = new ArrayList<>();
        try {
            for (int index = 0; index < files.length; index++) {
                MultipartFile file = files[index];
                String originalFilename = originalFilenames.get(index);
                String uploadId = UUID.randomUUID().toString().replace("-", "");
                Path target = normalizedDirectory.resolve(uploadId + ".upload");
                if (!normalizedDirectory.equals(target.getParent())) {
                    throw new IOException("Invalid JDBC driver upload target");
                }
                writeBounded(file, target, maxFileSizeBytes);
                stagedFiles.add(target);
                uploadTokens.add(uploadId + ":" + originalFilename);
            }
            return uploadTokens;
        } catch (IOException | RuntimeException exception) {
            for (Path stagedFile : stagedFiles) {
                Files.deleteIfExists(stagedFile);
            }
            throw exception;
        }
    }

    private static void writeBounded(MultipartFile file, Path target, long maxFileSizeBytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".driver-upload-", ".tmp");
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxFileSizeBytes) {
                    throw new IOException("JDBC driver file exceeds the size limit");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw new IOException("JDBC driver file is empty");
            }
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    static int cleanupExpiredUploads(Path stagingDirectory, Instant cutoff) throws IOException {
        if (!Files.isDirectory(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int deleted = 0;
        try (var paths = Files.list(stagingDirectory)) {
            for (Path path : paths.toList()) {
                String fileName = path.getFileName().toString();
                boolean managedTemporary = fileName.matches("[0-9a-f]{32}\\.upload")
                        || fileName.matches("\\.driver-upload-.*\\.tmp");
                if (!managedTemporary
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    continue;
                }
                if (Files.deleteIfExists(path)) {
                    deleted += 1;
                }
            }
        }
        return deleted;
    }
}
