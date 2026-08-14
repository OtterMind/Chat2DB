package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalVersionStoreTest {

    @Test
    void preservesExistingMetadataWhenTemporaryWriteFails(@TempDir Path directory) throws Exception {
        Path versionFile = directory.resolve("local_version.json");
        String original = "{\"version\":\"5.3.1\",\"files\":[]}";
        Files.writeString(versionFile, original);
        LocalVersionStore store = new LocalVersionStore(versionFile, new ObjectMapper(), (output, metadata) -> {
            output.write("{\"version\":\"5.3.2".getBytes(StandardCharsets.UTF_8));
            throw new IOException("simulated disk-full failure");
        }, ignored -> { }, ignored -> { });
        VersionMetadata metadata = new VersionMetadata();
        metadata.version = "5.3.2";

        IOException exception = assertThrows(IOException.class, () -> store.save(metadata));

        assertEquals("simulated disk-full failure", exception.getMessage());
        assertEquals(original, Files.readString(versionFile));
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp")));
        }
    }

    @Test
    void fallsBackToRegularReplacementWhenAtomicReplacementOfExistingMetadataFails(@TempDir Path directory)
            throws Exception {
        Path versionFile = directory.resolve("local_version.json");
        Files.writeString(versionFile, "{\"version\":\"5.3.1\",\"files\":[]}");
        AtomicBoolean atomicMoveAttempted = new AtomicBoolean();
        LocalVersionStore store = new LocalVersionStore(versionFile, new ObjectMapper(),
                (output, metadata) -> output.write("{\"version\":\"5.3.2\",\"files\":[]}".getBytes(StandardCharsets.UTF_8)),
                ignored -> { }, ignored -> { }, (source, target, options) -> {
                    for (var option : options) {
                        if (option == StandardCopyOption.ATOMIC_MOVE) {
                            atomicMoveAttempted.set(true);
                            throw new IOException("atomic replacement not supported for existing target");
                        }
                    }
                    Files.move(source, target, options);
                });
        VersionMetadata metadata = new VersionMetadata();
        metadata.version = "5.3.2";

        store.save(metadata);

        assertTrue(atomicMoveAttempted.get());
        assertEquals("{\"version\":\"5.3.2\",\"files\":[]}", Files.readString(versionFile));
    }
}
