package ai.chat2db.community.jcef.handler.biz;

import lombok.extern.slf4j.Slf4j;
import org.cef.CefApp;
import org.cef.callback.CefCallback;
import org.cef.callback.CefSchemeRegistrar;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public final class PreviewResourceScheme {
    public static final String SCHEME = "chat2db-resource";
    private static final String DOMAIN = "preview";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private PreviewResourceScheme() {
    }

    public static void registerScheme(CefSchemeRegistrar registrar) {
        boolean registered = registrar.addCustomScheme(
                SCHEME,
                true,
                false,
                false,
                true,
                true,
                false,
                false
        );
        if (!registered) {
            throw new IllegalStateException("Unable to register the Chat2DB preview resource scheme");
        }
    }

    public static void registerFactory(CefApp cefApp) {
        boolean registered = cefApp.registerSchemeHandlerFactory(
                SCHEME,
                DOMAIN,
                (browser, frame, schemeName, request) -> new PreviewResourceHandler()
        );
        if (!registered) {
            throw new IllegalStateException("Unable to register the Chat2DB preview resource handler");
        }
    }

    static String createUrl(String rootToken, String relativePath) {
        return createUrl(rootToken, relativePath, null);
    }

    static String createUrl(String rootToken, String relativePath, String version) {
        String encodedPath = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (relativePath == null ? "" : relativePath).getBytes(StandardCharsets.UTF_8)
        );
        String url = SCHEME + "://" + DOMAIN + "/" + rootToken + "/" + encodedPath;
        return version == null || version.isBlank()
                ? url
                : url + "?v=" + URLEncoder.encode(version, StandardCharsets.UTF_8);
    }

    static ResourceRequest resolveRequest(String url, String method, String rangeHeader, String ifNoneMatch,
                                          String ifRange)
            throws IOException {
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return ResourceRequest.error(405, "Method Not Allowed");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            return ResourceRequest.error(400, "Bad Request");
        }
        if (!SCHEME.equals(uri.getScheme()) || !DOMAIN.equals(uri.getHost())) {
            return ResourceRequest.error(404, "Not Found");
        }
        String[] segments = uri.getRawPath().split("/", -1);
        if (segments.length != 3 || segments[1].isBlank() || segments[2].isBlank()) {
            return ResourceRequest.error(404, "Not Found");
        }

        String relativePath;
        try {
            relativePath = new String(Base64.getUrlDecoder().decode(segments[2]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return ResourceRequest.error(400, "Bad Request");
        }

        SqlDirectoryTreeStore.PreviewResource resource;
        try {
            resource = SqlDirectoryTreeStore.resolvePreviewResource(segments[1], relativePath);
        } catch (IllegalArgumentException exception) {
            return ResourceRequest.error(404, "Not Found");
        }

        if (etagMatches(ifNoneMatch, resource.etag())) {
            return ResourceRequest.notModified(resource);
        }

        String effectiveRange = ifRange == null || ifRange.isBlank() || resource.etag().equals(ifRange.trim())
                ? rangeHeader
                : null;
        ByteRange range = parseRange(effectiveRange, resource.size());
        if (range == null) {
            return ResourceRequest.error(416, "Range Not Satisfiable", resource);
        }
        return ResourceRequest.success(resource, range.start(), range.end(), range.partial());
    }

    private static boolean etagMatches(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        for (String candidate : header.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value) || etag.equals(value) || ("W/" + etag).equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static ByteRange parseRange(String header, long size) {
        if (header == null || header.isBlank()) {
            return new ByteRange(0, Math.max(size - 1, 0), false);
        }
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || size <= 0) {
            return null;
        }
        String value = header.substring("bytes=".length()).trim();
        int separator = value.indexOf('-');
        if (separator < 0) {
            return null;
        }
        try {
            if (separator == 0) {
                long suffixLength = Long.parseLong(value.substring(1));
                if (suffixLength <= 0) {
                    return null;
                }
                long start = Math.max(size - suffixLength, 0);
                return new ByteRange(start, size - 1, true);
            }
            long start = Long.parseLong(value.substring(0, separator));
            long end = separator == value.length() - 1
                    ? size - 1
                    : Long.parseLong(value.substring(separator + 1));
            if (start < 0 || start >= size || end < start) {
                return null;
            }
            return new ByteRange(start, Math.min(end, size - 1), true);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    record ResourceRequest(
            int status,
            String statusText,
            SqlDirectoryTreeStore.PreviewResource resource,
            long start,
            long end,
            boolean partial
    ) {
        static ResourceRequest success(SqlDirectoryTreeStore.PreviewResource resource, long start, long end,
                                       boolean partial) {
            return new ResourceRequest(partial ? 206 : 200, partial ? "Partial Content" : "OK", resource,
                    start, end, partial);
        }

        static ResourceRequest notModified(SqlDirectoryTreeStore.PreviewResource resource) {
            return new ResourceRequest(304, "Not Modified", resource, 0, -1, false);
        }

        static ResourceRequest error(int status, String statusText) {
            return new ResourceRequest(status, statusText, null, 0, -1, false);
        }

        static ResourceRequest error(int status, String statusText, SqlDirectoryTreeStore.PreviewResource resource) {
            return new ResourceRequest(status, statusText, resource, 0, -1, false);
        }

        long contentLength() {
            return end >= start ? end - start + 1 : 0;
        }
    }

    private record ByteRange(long start, long end, boolean partial) {
    }

    private static final class PreviewResourceHandler extends CefResourceHandlerAdapter {
        private ResourceRequest resourceRequest = ResourceRequest.error(500, "Internal Server Error");
        private FileChannel channel;
        private long position;
        private long remaining;
        private boolean headRequest;

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            headRequest = "HEAD".equalsIgnoreCase(request.getMethod());
            try {
                resourceRequest = resolveRequest(
                        request.getURL(),
                        request.getMethod(),
                        request.getHeaderByName("Range"),
                        request.getHeaderByName("If-None-Match"),
                        request.getHeaderByName("If-Range")
                );
                if (!headRequest && (resourceRequest.status() == 200 || resourceRequest.status() == 206)) {
                    channel = FileChannel.open(resourceRequest.resource().path(), StandardOpenOption.READ);
                    position = resourceRequest.start();
                    remaining = resourceRequest.contentLength();
                }
            } catch (Exception exception) {
                log.warn("Unable to open preview resource", exception);
                resourceRequest = ResourceRequest.error(500, "Internal Server Error");
                closeChannel();
            }
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(resourceRequest.status());
            response.setStatusText(resourceRequest.statusText());
            Map<String, String> headers = new LinkedHashMap<>();
            SqlDirectoryTreeStore.PreviewResource resource = resourceRequest.resource();
            if (resource != null) {
                response.setMimeType(resource.mimeType());
                headers.put("Accept-Ranges", "bytes");
                headers.put("Cache-Control", "private, no-cache");
                headers.put("ETag", resource.etag());
                headers.put("Last-Modified", HTTP_DATE.format(Instant.ofEpochMilli(resource.lastModifiedMillis())));
                if (resourceRequest.status() == 416) {
                    headers.put("Content-Range", "bytes */" + resource.size());
                } else if (resourceRequest.status() == 206) {
                    headers.put("Content-Range", "bytes " + resourceRequest.start() + "-" + resourceRequest.end()
                            + "/" + resource.size());
                }
                if (resourceRequest.status() == 200 || resourceRequest.status() == 206) {
                    headers.put("Content-Length", Long.toString(resourceRequest.contentLength()));
                }
            } else {
                response.setMimeType("text/plain");
            }
            response.setHeaderMap(headers);
            responseLength.set(headRequest ? 0 : Math.toIntExact(resourceRequest.contentLength()));
        }

        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            if (channel == null || remaining <= 0 || bytesToRead <= 0) {
                bytesRead.set(0);
                closeChannel();
                return false;
            }
            int requested = (int) Math.min(Math.min((long) bytesToRead, remaining), dataOut.length);
            try {
                int count = channel.read(ByteBuffer.wrap(dataOut, 0, requested), position);
                if (count <= 0) {
                    bytesRead.set(0);
                    closeChannel();
                    return false;
                }
                position += count;
                remaining -= count;
                bytesRead.set(count);
                if (remaining == 0) {
                    closeChannel();
                }
                return true;
            } catch (IOException exception) {
                log.warn("Unable to read preview resource", exception);
                bytesRead.set(0);
                closeChannel();
                return false;
            }
        }

        @Override
        public void cancel() {
            closeChannel();
        }

        private void closeChannel() {
            if (channel == null) {
                return;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                log.debug("Unable to close preview resource", exception);
            } finally {
                channel = null;
            }
        }
    }
}
