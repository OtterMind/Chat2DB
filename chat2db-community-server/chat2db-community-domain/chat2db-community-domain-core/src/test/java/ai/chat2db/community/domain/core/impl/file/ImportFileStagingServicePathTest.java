package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.storage.imports.FileImportStagingStore;
import ai.chat2db.community.tools.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two staging behaviours an end-to-end run broke: resolving a staged file whose state
 * directory is reached through a symlink, and reporting an over-sized upload as a size problem.
 */
class ImportFileStagingServicePathTest {

    private String previousUserHome;

    @BeforeEach
    void rememberUserHome() {
        previousUserHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", previousUserHome);
    }

    @Test
    void resolvesAStagedFileWhenTheStateDirectoryIsBehindASymlink(@TempDir Path realHome) throws Exception {
        // macOS puts /tmp behind a symlink to /private/tmp; toRealPath() expands it, the staging
        // directory did not, so resolve() used to reject a perfectly readable file.
        Path link = realHome.getParent().resolve("state-link-" + System.nanoTime());
        Files.createSymbolicLink(link, realHome);
        System.setProperty("user.home", link.toString());
        ImportFileStagingService service = service();
        File source = Files.writeString(realHome.resolve("rows.csv"), "id\n1\n", StandardCharsets.UTF_8).toFile();

        String fileId = service.stage(source, "rows.csv");

        File resolved = service.resolve(fileId);

        assertTrue(resolved.isFile(), "a staged file behind a symlinked state directory must resolve");
        assertEquals("id\n1\n", Files.readString(resolved.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void stagesASelectedFileThroughASymlinkedParent(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        Path directory = Files.createDirectory(home.resolve("source"));
        Files.writeString(directory.resolve("rows.csv"), "id\n1\n", StandardCharsets.UTF_8);
        Path alias = Files.createSymbolicLink(home.resolve("source-alias"), directory);
        ImportFileStagingService service = service();

        String fileId = service.stage(alias.resolve("rows.csv").toFile(), "rows.csv");

        assertEquals("id\n1\n", Files.readString(service.resolve(fileId).toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidSourcePaths(@TempDir Path home) throws Exception {
        System.setProperty("user.home", home.toString());
        Path source = Files.writeString(home.resolve("rows.csv"), "id\n1\n", StandardCharsets.UTF_8);
        Path link = Files.createSymbolicLink(home.resolve("selected.csv"), source);
        ImportFileStagingService service = service();

        File[] invalidSources = {link.toFile(), home.toFile(), home.resolve("missing.csv").toFile(),
                new File("invalid\0.csv"), null};
        for (File invalid : invalidSources) {
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> service.stage(invalid, "rows.csv"));
            assertEquals("import.preview.fileUnreadable", failure.getCode());
        }
        assertEquals("id\n1\n", Files.readString(source, StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void rejectsAnOversizedUploadWithTheSizeMessage(@TempDir Path realHome) throws Exception {
        System.setProperty("user.home", realHome.toString());
        ImportFileStagingService service = service();
        File oversized = realHome.resolve("too-big.csv").toFile();
        try (RandomAccessFile file = new RandomAccessFile(oversized, "rw")) {
            file.setLength(51L * 1024 * 1024);
        }

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.stage(oversized, "too-big.csv"));

        assertEquals("import.preview.fileTooLarge", failure.getCode());
        assertEquals(50L, failure.getArgs()[0]);
    }

    /** The shipped locations, so the test covers the directory the product really writes to. */
    private ImportFileStagingService service() {
        FileImportStagingStore store = new FileImportStagingStore(new ObjectMapper(),
                FileImportStagingStore.defaultRootDirectory(), FileImportStagingStore.defaultLegacyDirectory());
        store.prepareRootDirectory();
        return new ImportFileStagingService(store, ImportFileStagingService.DEFAULT_MAX_TOTAL_BYTES);
    }
}
