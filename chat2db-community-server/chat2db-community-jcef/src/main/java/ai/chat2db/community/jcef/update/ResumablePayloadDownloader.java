package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.listener.IProgressListener;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Handles one verified, resumable payload transfer. It deliberately has no knowledge of update
 * state, installation, or JCEF; callers only provide safe paths, checksum verification and
 * optional progress reporting.
 */
final class ResumablePayloadDownloader {

    private static final long MAX_SINGLE_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    interface DownloadPaths {
        Path resolvePayload(String fileName) throws IOException;

        Path resolvePartial(Path payloadPath) throws IOException;
    }

    interface ChecksumVerifier {
        boolean matches(Path path, String expectedSha256) throws IOException, NoSuchAlgorithmException;
    }

    private final UpdateSource updateSource;
    private final DownloadPaths paths;
    private final ChecksumVerifier checksums;
    private final Consumer<String> progressLog;

    ResumablePayloadDownloader(UpdateSource updateSource, DownloadPaths paths, ChecksumVerifier checksums,
                               Consumer<String> progressLog) {
        this.updateSource = Objects.requireNonNull(updateSource, "updateSource is required");
        this.paths = Objects.requireNonNull(paths, "paths is required");
        this.checksums = Objects.requireNonNull(checksums, "checksums is required");
        this.progressLog = Objects.requireNonNull(progressLog, "progressLog is required");
    }

    Path download(String manifestVersion, FileInfo remoteFile, IProgressListener progressListener)
            throws IOException, NoSuchAlgorithmException {
        String targetFileName = remoteFile.serverFileName;
        String expectedSha256 = remoteFile.sha256;
        long expectedSize = remoteFile.fileSizeByte;
        Path targetPath = paths.resolvePayload(targetFileName);
        Path partialPath = paths.resolvePartial(targetPath);
        Files.createDirectories(targetPath.getParent());

        if (Files.exists(targetPath)) {
            progressLog.accept("File already exists, verifying: " + targetFileName);
            if (checksums.matches(targetPath, expectedSha256)) {
                if (Files.size(targetPath) != expectedSize) {
                    Files.deleteIfExists(targetPath);
                    throw new IOException("Existing update file size does not match metadata");
                }
                progressLog.accept("Checksum matches. Skipping download.");
                reportExistingFileSize(progressListener, targetPath);
                return targetPath;
            }
            progressLog.accept("Checksum mismatch. Re-downloading...");
            Files.deleteIfExists(targetPath);
        }

        long existingBytes = Files.exists(partialPath) ? Files.size(partialPath) : 0L;
        if (existingBytes > expectedSize) {
            progressLog.accept("Partial download exceeds expected size. Starting over.");
            Files.deleteIfExists(partialPath);
            existingBytes = 0L;
        }
        if (existingBytes == expectedSize) {
            if (checksums.matches(partialPath, expectedSha256)) {
                moveIntoPlace(partialPath, targetPath);
                if (progressListener != null) {
                    progressListener.onProgress(existingBytes);
                }
                return targetPath;
            }
            Files.deleteIfExists(partialPath);
            existingBytes = 0L;
        }

        progressLog.accept("Starting download: " + targetFileName + " from " + remoteFile.url);
        URI payloadUri;
        try {
            payloadUri = URI.create(remoteFile.url);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Update payload URL is invalid", exception);
        }

        MessageDigest sha256 = downloadOrResume(manifestVersion, remoteFile, payloadUri, partialPath, expectedSize,
                existingBytes, progressListener);
        if (Files.size(partialPath) != expectedSize) {
            Files.deleteIfExists(partialPath);
            throw new IOException("Update file size does not match metadata");
        }

        progressLog.accept("Verifying checksum for " + targetFileName);
        String actualSha256 = bytesToHex(sha256.digest());
        if (!actualSha256.equals(expectedSha256)) {
            Files.deleteIfExists(partialPath);
            throw new IOException("Checksum mismatch for " + partialPath.getFileName() + ". Expected: "
                    + expectedSha256 + ", Actual: " + actualSha256);
        }
        moveIntoPlace(partialPath, targetPath);
        progressLog.accept("Download & verification complete: " + targetFileName);
        return targetPath;
    }

    private MessageDigest downloadOrResume(String manifestVersion, FileInfo remoteFile, URI payloadUri, Path partialPath,
                                           long expectedSize, long existingBytes, IProgressListener progressListener)
            throws IOException, NoSuchAlgorithmException {
        while (true) {
            ValidatedPayloadRequest request = new ValidatedPayloadRequest(manifestVersion, remoteFile.serverFileName,
                    payloadUri, existingBytes);
            try (UpdateResponse response = updateSource.openPayload(request)) {
                if (existingBytes > 0) {
                    if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                        Files.deleteIfExists(partialPath);
                        existingBytes = 0L;
                        continue;
                    }
                    if (!isPartialResponseForOffset(existingBytes, expectedSize, response.statusCode(),
                            response.header("Content-Range"), response.contentLengthOrMinusOne())) {
                        Files.deleteIfExists(partialPath);
                        existingBytes = 0L;
                        continue;
                    }
                } else if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Update server returned unexpected HTTP status " + response.statusCode());
                }

                long contentLength = response.contentLengthOrMinusOne();
                if (contentLength > MAX_SINGLE_DOWNLOAD_BYTES) {
                    throw new IOException("Update file exceeds the download limit");
                }
                long expectedRemainingBytes = expectedSize - existingBytes;
                if (contentLength >= 0 && contentLength != expectedRemainingBytes) {
                    throw new IOException("Update file size does not match metadata");
                }

                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                if (existingBytes > 0) {
                    updateDigestFromFile(partialPath, sha256);
                    if (progressListener != null) {
                        progressListener.onProgress(existingBytes);
                    }
                }
                writePayload(response, partialPath, existingBytes, expectedSize, sha256, progressListener);
                return sha256;
            }
        }
    }

    private static void writePayload(UpdateResponse response, Path partialPath, long existingBytes, long expectedSize,
                                     MessageDigest sha256, IProgressListener progressListener) throws IOException {
        try (InputStream input = response.openStream();
             OutputStream output = Files.newOutputStream(partialPath, StandardOpenOption.CREATE,
                     existingBytes == 0 ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
            byte[] buffer = new byte[8192];
            long bytesWritten = existingBytes;
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                bytesWritten = Math.addExact(bytesWritten, bytesRead);
                if (bytesWritten > MAX_SINGLE_DOWNLOAD_BYTES || bytesWritten > expectedSize) {
                    throw new IOException("Update file exceeds its declared size");
                }
                output.write(buffer, 0, bytesRead);
                sha256.update(buffer, 0, bytesRead);
                if (progressListener != null) {
                    progressListener.onProgress(bytesRead);
                }
            }
            if (bytesWritten != expectedSize) {
                throw new IOException("Update file size does not match metadata");
            }
        }
    }

    static boolean isPartialResponseForOffset(long existingBytes, long expectedSize, int responseCode,
                                              String contentRange, long contentLength) {
        if (existingBytes <= 0 || responseCode != HttpURLConnection.HTTP_PARTIAL || contentRange == null) {
            return false;
        }
        return contentRange.startsWith("bytes " + existingBytes + "-")
                && contentRange.endsWith("/" + expectedSize)
                && (contentLength < 0 || contentLength == expectedSize - existingBytes);
    }

    private static void reportExistingFileSize(IProgressListener progressListener, Path targetPath) {
        if (progressListener == null) {
            return;
        }
        try {
            progressListener.onProgress(Files.size(targetPath));
        } catch (IOException ignored) {
            // Progress is advisory; verification already succeeded.
        }
    }

    private static void updateDigestFromFile(Path path, MessageDigest digest) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder result = new StringBuilder(2 * hash.length);
        for (byte value : hash) {
            String hex = Integer.toHexString(0xff & value);
            if (hex.length() == 1) {
                result.append('0');
            }
            result.append(hex);
        }
        return result.toString();
    }
}
