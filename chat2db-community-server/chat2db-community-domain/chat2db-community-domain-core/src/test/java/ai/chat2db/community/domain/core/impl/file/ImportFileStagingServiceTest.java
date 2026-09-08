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

class ImportFileStagingServiceTest {

    @TempDir
    private Path tempDirectory;

    private final String originalUserHome = System.getProperty("user.home");

    private final String originalMaxSize = System.getProperty("chat2db.task.import.staging.max-bytes");

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
        if (originalMaxSize == null) {
            System.clearProperty("chat2db.task.import.staging.max-bytes");
        } else {
            System.setProperty("chat2db.task.import.staging.max-bytes", originalMaxSize);
        }
    }

    @Test
    void registersResolvesAndReleasesOnlyStagedImportFile() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File source = Files.writeString(tempDirectory.resolve("source.csv"), "id,name\n1,Ada\n").toFile();

        String fileId = stagingService.stage(source, "input.csv");
        File resolved = stagingService.resolve(fileId);

        assertTrue(resolved.isFile());
        assertEquals(fileId + ".csv", resolved.getName());
        assertTrue(resolved.toPath().normalize().toAbsolutePath().startsWith(tempDirectory));

        stagingService.release(fileId);

        assertFalse(Files.exists(resolved.toPath()));
    }

    @Test
    void rejectsInvalidFileId() {
        System.setProperty("user.home", tempDirectory.toString());

        assertThrows(BusinessException.class, () -> new ImportFileStagingService().resolve("../outside"));
    }

    @Test
    void acceptsEverySupportedWebImportFormat() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        ImportFileStagingService stagingService = new ImportFileStagingService();

        for (String extension : new String[] {"csv", "xls", "xlsx", "json", "sql"}) {
            File source = Files.writeString(tempDirectory.resolve("source." + extension), "test").toFile();
            String fileId = stagingService.stage(source, "input." + extension);
            File resolved = stagingService.resolve(fileId);

            assertEquals(fileId + "." + extension, resolved.getName());
            stagingService.release(fileId);
        }
    }

    @Test
    void rejectsUnsupportedImportFormat() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        File source = Files.writeString(tempDirectory.resolve("source.zip"), "test").toFile();

        assertThrows(BusinessException.class, () -> new ImportFileStagingService().stage(source, "input.zip"));
    }

    @Test
    void appliesTheConfigurableImportSizeLimit() throws Exception {
        System.setProperty("user.home", tempDirectory.toString());
        System.setProperty("chat2db.task.import.staging.max-bytes", "16");
        ImportFileStagingService stagingService = new ImportFileStagingService();
        File accepted = Files.writeString(tempDirectory.resolve("accepted.csv"), "abcdefghijklmnop").toFile();
        File rejected = Files.writeString(tempDirectory.resolve("rejected.csv"), "abcdefghijklmnopq").toFile();

        String fileId = stagingService.stage(accepted, "accepted.csv");
        assertThrows(BusinessException.class, () -> stagingService.stage(rejected, "rejected.csv"));
        stagingService.release(fileId);
    }
}
