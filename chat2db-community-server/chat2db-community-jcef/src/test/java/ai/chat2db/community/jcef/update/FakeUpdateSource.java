package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test-only update source for unit and contract tests. Not shipped in production.
 */
final class FakeUpdateSource implements UpdateSource {

    private byte[] manifestBytes;
    private long fetchedAtNanos = 0;
    private Map<String, byte[]> payloads = Map.of();
    private IOException manifestException;
    private IOException payloadException;
    private PayloadResponseMode payloadResponseMode = PayloadResponseMode.PARTIAL_CONTENT;
    private final AtomicInteger fetchCount = new AtomicInteger(0);
    private final AtomicInteger versionManifestFetchCount = new AtomicInteger(0);
    private final AtomicInteger payloadRequestCount = new AtomicInteger(0);
    private final AtomicLong nanoTime = new AtomicLong(0);
    private final AtomicReference<ValidatedPayloadRequest> lastPayloadRequest = new AtomicReference<>();

    FakeUpdateSource manifest(byte[] bytes) {
        this.manifestBytes = bytes != null ? bytes.clone() : null;
        return this;
    }

    FakeUpdateSource manifest(String json) {
        return manifest(json.getBytes(StandardCharsets.UTF_8));
    }

    FakeUpdateSource payloads(Map<String, byte[]> payloads) {
        this.payloads = payloads != null ? Map.copyOf(payloads) : Map.of();
        return this;
    }

    FakeUpdateSource manifestException(IOException exception) {
        this.manifestException = exception;
        return this;
    }

    FakeUpdateSource payloadException(IOException exception) {
        this.payloadException = exception;
        return this;
    }

    FakeUpdateSource payloadResponseMode(PayloadResponseMode mode) {
        this.payloadResponseMode = mode == null ? PayloadResponseMode.PARTIAL_CONTENT : mode;
        return this;
    }

    FakeUpdateSource fetchedAtNanos(long fetchedAtNanos) {
        this.fetchedAtNanos = fetchedAtNanos;
        return this;
    }

    long nextNanoTime() {
        return nanoTime.incrementAndGet();
    }

    void setNanoTime(long value) {
        nanoTime.set(value);
    }

    int fetchCount() {
        return fetchCount.get();
    }

    int versionManifestFetchCount() {
        return versionManifestFetchCount.get();
    }

    ValidatedPayloadRequest lastPayloadRequest() {
        return lastPayloadRequest.get();
    }

    int payloadRequestCount() {
        return payloadRequestCount.get();
    }

    @Override
    public FetchedUpdateManifest fetchLatestManifest() throws IOException {
        fetchCount.incrementAndGet();
        if (manifestException != null) {
            throw manifestException;
        }
        if (manifestBytes == null) {
            fail("Manifest bytes not configured");
        }
        JsonNode root = new ObjectMapper().readTree(manifestBytes);
        String version = text(root, "version");
        String releaseNotes = text(root, "releaseNotes");
        String releasePageUrl = text(root, "releasePageUrl");
        Boolean forceUpdate = root.has("forceUpdate") ? root.get("forceUpdate").asBoolean() : null;
        return new FetchedUpdateManifest(manifestBytes, version, releaseNotes, releasePageUrl, forceUpdate,
                sha256(manifestBytes), fetchedAtNanos);
    }

    @Override
    public byte[] fetchVersionManifest(String version) throws IOException {
        versionManifestFetchCount.incrementAndGet();
        if (manifestException != null) {
            throw manifestException;
        }
        if (manifestBytes == null) {
            fail("Manifest bytes not configured");
        }
        return manifestBytes.clone();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    @Override
    public UpdateResponse openPayload(ValidatedPayloadRequest request) throws IOException {
        lastPayloadRequest.set(request);
        payloadRequestCount.incrementAndGet();
        if (payloadException != null) {
            throw payloadException;
        }
        String assetName = request.assetName();
        byte[] bytes = payloads.get(assetName);
        if (bytes == null) {
            throw new IOException("Unknown test payload: " + assetName);
        }
        long offset = request.rangeOffset();
        if (offset < 0 || offset > bytes.length) {
            throw new IOException("Invalid range offset: " + offset);
        }
        if (offset == 0) {
            return new ByteArrayUpdateResponse(bytes, bytes.length, 200, null);
        }
        return switch (payloadResponseMode) {
            case PARTIAL_CONTENT -> new ByteArrayUpdateResponse(
                    Arrays.copyOfRange(bytes, (int) offset, bytes.length), bytes.length - offset,
                    206, "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
            case RANGE_IGNORED -> new ByteArrayUpdateResponse(bytes, bytes.length, 200, null);
            case WRONG_CONTENT_RANGE -> new ByteArrayUpdateResponse(
                    Arrays.copyOfRange(bytes, (int) offset, bytes.length), bytes.length - offset,
                    206, "bytes 0-" + (bytes.length - 1) + "/" + bytes.length);
            case TRUNCATED_PARTIAL -> new ByteArrayUpdateResponse(
                    Arrays.copyOfRange(bytes, (int) offset, bytes.length - 1), bytes.length - offset,
                    206, "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
        };
    }

    static UpdateResponse openFilePayload(Path file, long offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (offset < 0 || offset > bytes.length) {
            throw new IOException("Invalid range offset: " + offset);
        }
        long length = bytes.length - offset;
        byte[] slice = Arrays.copyOfRange(bytes, (int) offset, bytes.length);
        return new ByteArrayUpdateResponse(slice, length, offset > 0 ? 206 : 200,
                offset > 0 ? "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length : null);
    }

    enum PayloadResponseMode {
        PARTIAL_CONTENT,
        RANGE_IGNORED,
        WRONG_CONTENT_RANGE,
        TRUNCATED_PARTIAL
    }

    private static final class ByteArrayUpdateResponse implements UpdateResponse {
        private final byte[] bytes;
        private final long contentLength;
        private final int statusCode;
        private final String contentRange;
        private boolean closed;

        ByteArrayUpdateResponse(byte[] bytes, long contentLength, int statusCode, String contentRange) {
            this.bytes = bytes;
            this.contentLength = contentLength;
            this.statusCode = statusCode;
            this.contentRange = contentRange;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public long contentLengthOrMinusOne() {
            return contentLength;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public String header(String name) {
            return "Content-Range".equalsIgnoreCase(name) ? contentRange : null;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }
}
