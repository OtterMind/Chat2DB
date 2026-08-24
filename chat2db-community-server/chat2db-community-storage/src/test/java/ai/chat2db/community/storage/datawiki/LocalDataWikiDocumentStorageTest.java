package ai.chat2db.community.storage.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocument;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocumentBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDataWikiDocumentStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void synchronizesIndexTableDocumentsAndManifestWithoutLeavingStaleFiles() throws Exception {
        LocalDataWikiDocumentStorage storage = new LocalDataWikiDocumentStorage(tempDirectory);
        DataWikiDocument readme = document("README.md", "README", "# Wiki");
        DataWikiDocument table = document("tables/sales/orders.md", "TABLE", "# Orders");

        Path root = Path.of(storage.synchronize("wiki-1", 1L, List.of(readme, table)));

        assertEquals("# Wiki", Files.readString(root.resolve("README.md")));
        assertEquals("# Orders", Files.readString(root.resolve("tables/sales/orders.md")));
        assertTrue(Files.readString(root.resolve("manifest.json")).contains("\"revision\":1"));
        DataWikiDocumentBundle bundle = storage.load("wiki-1", 1L);
        assertEquals("# Wiki", bundle.getDocuments().get(0).getContent());
        assertNull(bundle.getDocuments().get(1).getContent());
        assertEquals("# Orders", storage.read("wiki-1", "tables/sales/orders.md"));
        assertThrows(IllegalArgumentException.class, () -> storage.read("wiki-1", "not-declared.md"));

        storage.synchronize("wiki-1", 2L, List.of(document("README.md", "README", "# Wiki v2")));
        assertEquals("# Wiki v2", Files.readString(root.resolve("README.md")));
        assertFalse(Files.exists(root.resolve("tables/sales/orders.md")));

        storage.delete("wiki-1");
        assertFalse(Files.exists(root));
    }

    @Test
    void rejectsPathsOutsideTheWikiDirectory() {
        LocalDataWikiDocumentStorage storage = new LocalDataWikiDocumentStorage(tempDirectory);
        assertThrows(IllegalArgumentException.class,
                () -> storage.synchronize("wiki-1", 1L, List.of(document("../escape.md", "TABLE", "bad"))));
    }

    private static DataWikiDocument document(String path, String kind, String content) {
        DataWikiDocument document = new DataWikiDocument();
        document.setPath(path);
        document.setTitle(path);
        document.setKind(kind);
        document.setContent(content);
        return document;
    }
}
