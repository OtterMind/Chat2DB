package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Validates the immutable update manifest before it can influence downloads or installation.
 */
final class ManifestValidator {

    static final long MAX_TOTAL_DOWNLOAD_BYTES = 4L * 1024 * 1024 * 1024;

    private static final String SHA_256_PATTERN = "^[a-f0-9]{64}$";
    private static final long MAX_SINGLE_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String GITHUB_HOST = "github.com";
    private static final String RELEASE_DOWNLOAD_PREFIX = "/OtterMind/Chat2DB/releases/download/";

    interface PathResolver {
        Path resolveApplicationTarget(String relativePath) throws IOException;

        Path resolveTemporaryPayload(String fileName) throws IOException;
    }

    private final PathResolver paths;

    ManifestValidator(PathResolver paths) {
        this.paths = Objects.requireNonNull(paths, "paths is required");
    }

    void validate(VersionMetadata metadata) throws IOException {
        validate(metadata, null);
    }

    void validate(VersionMetadata metadata, String expectedVersion) throws IOException {
        if (metadata == null) {
            throw new IOException("Update metadata is null");
        }
        if (isBlank(metadata.version)) {
            throw new IOException("Update metadata version is blank");
        }
        if (expectedVersion != null && !expectedVersion.equals(metadata.version)) {
            throw new IOException("Update metadata version does not match the discovered version");
        }
        if (metadata.launchCommand != null && !metadata.launchCommand.isEmpty()) {
            throw new IOException("Update metadata contains a remote launchCommand");
        }
        if (metadata.files == null || metadata.files.isEmpty()) {
            throw new IOException("Update metadata must declare at least one file");
        }

        Set<String> fileIds = new HashSet<>();
        Set<Path> localTargets = new HashSet<>();
        Set<Path> temporaryPayloads = new HashSet<>();
        long totalSize = 0L;
        for (FileInfo file : metadata.files) {
            if (file == null || isBlank(file.id) || !fileIds.add(file.id)) {
                throw new IOException("Update metadata contains a missing or duplicate file id");
            }
            Path localTarget = paths.resolveApplicationTarget(file.localTargetName);
            if (!localTargets.add(localTarget)) {
                throw new IOException("Update metadata contains a duplicate localTargetName for " + file.id);
            }
            if (file.deleted) {
                continue;
            }
            Path temporaryPayload = paths.resolveTemporaryPayload(file.serverFileName);
            if (!temporaryPayloads.add(temporaryPayload)) {
                throw new IOException("Update metadata contains a duplicate serverFileName for " + file.id);
            }
            validatePayload(file, metadata.version);
            try {
                totalSize = Math.addExact(totalSize, file.fileSizeByte);
            } catch (ArithmeticException exception) {
                throw new IOException("Update metadata download size overflow", exception);
            }
        }
        if (totalSize > MAX_TOTAL_DOWNLOAD_BYTES) {
            throw new IOException("Update metadata exceeds the total download limit");
        }
    }

    private static void validatePayload(FileInfo file, String version) throws IOException {
        if (isBlank(file.sha256) || !file.sha256.matches(SHA_256_PATTERN)) {
            throw new IOException("Update metadata has an invalid SHA-256 for " + file.id);
        }
        if (file.fileSizeByte < 0) {
            throw new IOException("Update metadata has a negative file size for " + file.id);
        }
        if (file.fileSizeByte > MAX_SINGLE_DOWNLOAD_BYTES) {
            throw new IOException("Update metadata file exceeds the download limit for " + file.id);
        }
        if (!"jar".equals(file.type) && !"zip".equals(file.type)) {
            throw new IOException("Update metadata has an unsupported file type for " + file.id);
        }
        validatePayloadUrl(file.url, version, file.serverFileName);
    }

    private static void validatePayloadUrl(String url, String version, String assetName) throws IOException {
        if (isBlank(url)) {
            throw new IOException("Update payload URL is blank");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Update payload URL is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Update payload URL must use HTTPS");
        }
        if (!GITHUB_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Update payload URL is outside of the GitHub update channel");
        }
        if (uri.getPort() != -1) {
            throw new IOException("Update payload URL must use the default HTTPS port");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("Update payload URL must not contain credentials");
        }
        if (uri.getQuery() != null) {
            throw new IOException("Update payload URL must not contain a query string");
        }
        if (uri.getFragment() != null) {
            throw new IOException("Update payload URL must not contain a fragment");
        }
        String path = uri.getPath();
        if (path == null) {
            throw new IOException("Update payload URL has no path");
        }
        String expectedPrefix = RELEASE_DOWNLOAD_PREFIX + "v" + version + "/";
        if (!path.startsWith(expectedPrefix)) {
            throw new IOException("Update payload URL is not under the expected release path");
        }
        String asset = path.substring(expectedPrefix.length());
        if (asset.isEmpty() || asset.contains("/") || asset.contains("\\") || ".".equals(asset) || "..".equals(asset)) {
            throw new IOException("Update payload URL has an invalid asset name");
        }
        if (!asset.equals(assetName)) {
            throw new IOException("Update payload URL asset name does not match serverFileName");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
