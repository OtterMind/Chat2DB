package ai.chat2db.community.jcef.handler.biz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PreviewResourceSchemeTest {
    @TempDir
    Path directory;

    @Test
    void resolvesSingleAndSuffixRanges() throws Exception {
        Files.write(directory.resolve("image.png"), new byte[10]);
        Map<String, Object> root = SqlDirectoryTreeStore.createRoot(directory.toString());
        String rootToken = (String) root.get("rootToken");
        String url = PreviewResourceScheme.createUrl(rootToken, "image.png");

        PreviewResourceScheme.ResourceRequest range = PreviewResourceScheme.resolveRequest(
                url, "GET", "bytes=2-5", null, null
        );
        assertEquals(206, range.status());
        assertEquals(2, range.start());
        assertEquals(5, range.end());
        assertEquals(4, range.contentLength());

        PreviewResourceScheme.ResourceRequest suffix = PreviewResourceScheme.resolveRequest(
                url, "GET", "bytes=-3", null, null
        );
        assertEquals(7, suffix.start());
        assertEquals(9, suffix.end());
    }

    @Test
    void returnsNotModifiedAndRejectsInvalidRanges() throws Exception {
        Files.write(directory.resolve("document.pdf"), new byte[8]);
        Map<String, Object> root = SqlDirectoryTreeStore.createRoot(directory.toString());
        String rootToken = (String) root.get("rootToken");
        Map<String, Object> preview = SqlDirectoryTreeStore.readPreview(rootToken, "document.pdf");
        String url = (String) preview.get("url");
        String etag = (String) preview.get("etag");
        assertNotNull(etag);

        assertEquals(304, PreviewResourceScheme.resolveRequest(url, "GET", null, "W/" + etag, null).status());
        assertEquals(304, PreviewResourceScheme.resolveRequest(url, "GET", null, "\"other\", " + etag, null).status());
        assertEquals(416, PreviewResourceScheme.resolveRequest(url, "GET", "bytes=9-10", null, null).status());
        assertEquals(416, PreviewResourceScheme.resolveRequest(url, "GET", "bytes=0-1,4-5", null, null).status());
        assertEquals(405, PreviewResourceScheme.resolveRequest(url, "POST", null, null, null).status());

        PreviewResourceScheme.ResourceRequest staleRange = PreviewResourceScheme.resolveRequest(
                url, "GET", "bytes=2-4", null, "\"stale\""
        );
        assertEquals(200, staleRange.status());
        assertEquals(8, staleRange.contentLength());
        assertEquals(206, PreviewResourceScheme.resolveRequest(url, "GET", "bytes=2-4", null, etag).status());
        assertNotNull(new URI(url).getQuery());
    }
}
