package ai.chat2db.community.jcef.handler.biz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDirectoryTreeStoreTest {
    @TempDir
    Path directory;

    @Test
    void listsAndReadsSupportedBinaryPreviews() throws Exception {
        byte[] imageBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        Files.write(directory.resolve("diagram.png"), imageBytes);
        Files.writeString(directory.resolve("document.pdf"), "%PDF-1.7");
        Files.write(directory.resolve("archive.zip"), new byte[]{1, 2, 3});

        Map<String, Object> root = SqlDirectoryTreeStore.createRoot(directory.toString());
        String rootToken = (String) root.get("rootToken");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) root.get("children");

        assertEquals(3, children.size());
        assertEquals(
                2,
                children.stream().filter(node -> Boolean.TRUE.equals(node.get("previewFile"))).count()
        );
        assertTrue(children.stream().anyMatch(node -> "archive.zip".equals(node.get("name"))));

        Map<String, Object> preview = SqlDirectoryTreeStore.readPreview(rootToken, "diagram.png");
        assertEquals("image/png", preview.get("mimeType"));
        assertEquals((long) imageBytes.length, preview.get("size"));
        assertTrue(((String) preview.get("url")).startsWith("chat2db-resource://preview/" + rootToken + "/"));
        assertTrue(((String) preview.get("etag")).startsWith("\""));
    }

    @Test
    void previewCannotEscapeTheOpenedRoot() throws Exception {
        Map<String, Object> root = SqlDirectoryTreeStore.createRoot(directory.toString());
        String rootToken = (String) root.get("rootToken");

        assertThrows(
                IllegalArgumentException.class,
                () -> SqlDirectoryTreeStore.readPreview(rootToken, "../outside.png")
        );
    }

    @Test
    void previewCannotEscapeThroughAnIntermediateSymbolicLink() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        Path rootDirectory = Files.createDirectory(directory.resolve("root"));
        Path outsideDirectory = Files.createDirectory(directory.resolve("outside"));
        Files.write(outsideDirectory.resolve("outside.png"), new byte[]{1, 2, 3});
        Files.createSymbolicLink(rootDirectory.resolve("linked"), outsideDirectory);
        Map<String, Object> root = SqlDirectoryTreeStore.createRoot(rootDirectory.toString());

        assertThrows(
                IllegalArgumentException.class,
                () -> SqlDirectoryTreeStore.readPreview((String) root.get("rootToken"), "linked/outside.png")
        );
    }
}
