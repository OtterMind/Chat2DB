package ai.chat2db.community.tools.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsFileStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void serializesConcurrentWholeFileUpdatesWithoutLosingKeys() throws Exception {
        Path settingsFile = tempDirectory.resolve("settings.json");
        SettingsFileStore store = new SettingsFileStore(settingsFile);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> updates = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                int keyIndex = index;
                updates.add(() -> {
                    store.setProperty("key-" + keyIndex, keyIndex);
                    return null;
                });
            }
            updates.add(() -> {
                store.setProperty("enableMcp", true);
                return null;
            });
            updates.add(() -> {
                store.resetToken("mcpAuthToken");
                return null;
            });
            executor.invokeAll(updates).forEach(future -> {
                try {
                    future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> persisted = new ObjectMapper().readValue(
                Files.readString(settingsFile),
                new TypeReference<Map<String, Object>>() {
                }
        );
        for (int index = 0; index < 64; index++) {
            assertEquals(index, ((Number) persisted.get("key-" + index)).intValue());
        }
        assertEquals(true, persisted.get("enableMcp"));
        assertNotNull(persisted.get("mcpAuthToken"));
        try (Stream<Path> files = Files.list(tempDirectory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void reusesExistingTokenAndRotatesOnlyWhenRequested() {
        SettingsFileStore store = new SettingsFileStore(tempDirectory.resolve("settings.json"));

        String first = store.getOrCreateToken("mcpAuthToken");
        assertEquals(first, store.getOrCreateToken("mcpAuthToken"));
        assertTrue(first.length() > 20);
        assertFalse(first.equals(store.resetToken("mcpAuthToken")));
    }

    @Test
    void propagatesWriteFailure() throws Exception {
        Path nonDirectoryParent = tempDirectory.resolve("not-a-directory");
        Files.writeString(nonDirectoryParent, "content");
        SettingsFileStore store = new SettingsFileStore(nonDirectoryParent.resolve("settings.json"));

        assertThrows(UncheckedIOException.class, () -> store.setProperty("enableMcp", true));
    }
}
