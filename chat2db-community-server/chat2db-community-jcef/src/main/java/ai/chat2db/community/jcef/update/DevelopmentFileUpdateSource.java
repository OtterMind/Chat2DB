package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Explicitly configured, development-only update source backed by local files.
 * Production selection is guarded by {@link DesktopUpdateSourceFactory}.
 */
final class DevelopmentFileUpdateSource implements UpdateSource {

    private static final long MAX_LATEST_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_VERSION_MANIFEST_BYTES = 1024 * 1024;
    private final Path rootDirectory;

    DevelopmentFileUpdateSource(Path rootDirectory) {
        if (rootDirectory == null || !rootDirectory.isAbsolute()) {
            throw new IllegalArgumentException("Development update directory must be an absolute path");
        }
        this.rootDirectory = rootDirectory.normalize();
    }

    Path rootDirectory() {
        return rootDirectory;
    }

    @Override
    public FetchedUpdateManifest fetchLatestManifest() throws IOException {
        byte[] bytes = readBoundedFile("latest_version.json", MAX_LATEST_MANIFEST_BYTES);
        DiscoveryManifestParser.DiscoveryManifest manifest = DiscoveryManifestParser.parse(bytes);
        return new FetchedUpdateManifest(bytes, manifest.version(), manifest.releaseNotes(),
                manifest.releasePageUrl(), manifest.forceUpdate(), manifest.metadataSha256(), System.nanoTime());
    }

    @Override
    public byte[] fetchVersionManifest(String version) throws IOException {
        UpdateChecker.validateVersion(version);
        return readBoundedFile("version.json", MAX_VERSION_MANIFEST_BYTES);
    }

    @Override
    public UpdateResponse openPayload(ValidatedPayloadRequest request) throws IOException {
        Path payload = resolveRegularFile(request.assetName());
        long size = Files.size(payload);
        long offset = request.rangeOffset();
        if (offset < 0 || offset > size || (offset > 0 && offset == size)) {
            throw new IOException("Invalid local payload range offset: " + offset);
        }
        return new FileUpdateResponse(payload, size, offset);
    }

    private byte[] readBoundedFile(String fileName, long maximumBytes) throws IOException {
        Path file = resolveRegularFile(fileName);
        long size = Files.size(file);
        if (size > maximumBytes) {
            throw new IOException(fileName + " exceeds " + maximumBytes + " bytes");
        }
        return Files.readAllBytes(file);
    }

    private Path resolveRegularFile(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank() || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new IOException("Invalid local update asset name: " + fileName);
        }
        Path realRoot = rootDirectory.toRealPath();
        Path candidate = realRoot.resolve(fileName).normalize();
        if (!candidate.startsWith(realRoot) || !Files.isRegularFile(candidate)) {
            throw new IOException("Local update asset not found: " + fileName);
        }
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot) || !Files.isRegularFile(realCandidate)) {
            throw new IOException("Local update asset escapes the configured directory: " + fileName);
        }
        return realCandidate;
    }

    private static final class FileUpdateResponse implements UpdateResponse {

        private final Path path;
        private final long size;
        private final long offset;
        private InputStream stream;

        private FileUpdateResponse(Path path, long size, long offset) {
            this.path = path;
            this.size = size;
            this.offset = offset;
        }

        @Override
        public InputStream openStream() throws IOException {
            if (stream == null) {
                stream = Files.newInputStream(path, StandardOpenOption.READ);
                stream.skipNBytes(offset);
            }
            return stream;
        }

        @Override
        public long contentLengthOrMinusOne() {
            return size - offset;
        }

        @Override
        public int statusCode() {
            return offset > 0 ? 206 : 200;
        }

        @Override
        public String header(String name) {
            if (offset == 0 || !"Content-Range".equalsIgnoreCase(name)) {
                return null;
            }
            return "bytes " + offset + "-" + (size - 1) + "/" + size;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }
}
