package ai.chat2db.community.tools.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileUtilsTest {

    @TempDir
    Path directory;

    @Test
    void roundTripsTypedArraysAndClearsAnExistingFile() throws IOException {
        Path path = directory.resolve("nested/records.json");
        assertTrue(JsonFileUtils.readArray(path.toFile(), Entry.class).isEmpty());
        assertFalse(Files.exists(path));
        List<Entry> entries = List.of(new Entry("订单", 2));

        JsonFileUtils.writeAtomically(path.toFile(), entries);

        assertEquals(entries, JsonFileUtils.readArray(path.toFile(), Entry.class));
        JsonFileUtils.writeAtomically(path.toFile(), List.of());
        assertEquals("[]", Files.readString(path));
        assertFalse(Files.exists(path.resolveSibling("records.json.part")));
    }

    @Test
    void malformedOrNullDocumentsArePreservedAndRejected() throws IOException {
        Path path = directory.resolve("records.json");
        for (String content : List.of("broken JSON", "null")) {
            Files.writeString(path, content);
            assertThrows(RuntimeException.class, () -> JsonFileUtils.readArray(path.toFile(), Entry.class));
            assertEquals(content, Files.readString(path));
        }
    }

    @Test
    void failedWritePreservesThePreviousDocumentAndCanBeRetried() throws IOException {
        Path path = directory.resolve("records.json");
        List<Entry> original = List.of(new Entry("original", 1));
        JsonFileUtils.writeAtomically(path.toFile(), original);
        Path blocked = Files.createDirectory(directory.resolve("records.json.part"));
        Path child = Files.writeString(blocked.resolve("child"), "keep");

        assertThrows(IOException.class, () -> JsonFileUtils.writeAtomically(path.toFile(), List.of()));

        assertEquals(original, JsonFileUtils.readArray(path.toFile(), Entry.class));
        assertEquals("keep", Files.readString(child));
        Files.delete(child);
        Files.delete(blocked);
        JsonFileUtils.writeAtomically(path.toFile(), List.of());
        assertTrue(JsonFileUtils.readArray(path.toFile(), Entry.class).isEmpty());
    }

    record Entry(String name, int attempts) {
    }
}
