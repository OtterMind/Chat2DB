package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFileRegistryTest {

    @TempDir
    private Path tempDirectory;

    private final String originalUserHome = System.getProperty("user.home");

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void registersResolvesAndReleasesOnlyStagedImportFile() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileRegistry registry = new ImportFileRegistry();
        File source = Files.writeString(tempDirectory.resolve("source.csv"), "id,name\n1,Ada\n").toFile();

        String fileId = registry.register(source, "input.csv");
        File resolved = registry.resolve(fileId);

        assertTrue(resolved.isFile());
        assertEquals(fileId + ".csv", resolved.getName());
        assertTrue(resolved.toPath().normalize().toAbsolutePath().startsWith(tempDirectory));

        registry.release(fileId);

        assertFalse(Files.exists(resolved.toPath()));
    }

    @Test
    void rejectsInvalidFileId() {
        System.setProperty("user.home", tempDirectory.toString());

        assertThrows(BusinessException.class, () -> new ImportFileRegistry().resolve("../outside"));
    }
}
