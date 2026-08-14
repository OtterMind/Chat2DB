package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only {@link UpdateSource} used for gray E2E and updater integration tests.
 * It is never compiled into production artifacts and is not selectable through
 * normal environment variables, system properties, or renderer requests.
 */
public final class TestUpdateSource implements UpdateSource {

    private final FetchedUpdateManifest manifest;
    private final Map<String, Path> assets;

    public TestUpdateSource(FetchedUpdateManifest manifest, Map<String, Path> assets) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    @Override
    public FetchedUpdateManifest fetchLatestManifest() {
        return manifest;
    }

    @Override
    public UpdateResponse openPayload(ValidatedPayloadRequest request) throws IOException {
        Path path = assets.get(request.assetName());
        if (path == null) {
            throw new IOException("Asset not found in test source: " + request.assetName());
        }
        if (!Files.exists(path)) {
            throw new IOException("Asset file missing: " + path);
        }
        return new FileUpdateResponse(path);
    }

    private static final class FileUpdateResponse implements UpdateResponse {

        private final Path path;
        private InputStream stream;

        FileUpdateResponse(Path path) {
            this.path = path;
        }

        @Override
        public InputStream openStream() throws IOException {
            if (stream == null) {
                stream = Files.newInputStream(path);
            }
            return stream;
        }

        @Override
        public long contentLengthOrMinusOne() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public String header(String name) {
            return null;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }
}
