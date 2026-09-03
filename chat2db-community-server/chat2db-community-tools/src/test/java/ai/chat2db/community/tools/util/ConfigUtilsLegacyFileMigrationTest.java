package ai.chat2db.community.tools.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUtilsLegacyFileMigrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void legacyConfigAndClientIdFilesAreCopiedToCurrentNames() throws IOException {
        Path configDirectory = Files.createDirectories(temporaryDirectory.resolve("config"));
        Path legacyConfig = write(configDirectory.resolve("enterprise_config_dev.json"),
                "{\"systemUuid\":\"keep-me\"}".getBytes());
        Path legacyClientId = write(configDirectory.resolve("enterprise_client_uuid"),
                "client-id-123".getBytes());
        File currentConfig = configDirectory.resolve("runtime_config_dev.json").toFile();
        File currentClientId = configDirectory.resolve("client_uuid").toFile();

        ConfigUtils.migrateLegacyFile(legacyConfig.toFile(), currentConfig, "runtime config");
        ConfigUtils.migrateLegacyFile(legacyClientId.toFile(), currentClientId, "client id");

        assertArrayEquals(Files.readAllBytes(legacyConfig), Files.readAllBytes(currentConfig.toPath()));
        assertArrayEquals(Files.readAllBytes(legacyClientId), Files.readAllBytes(currentClientId.toPath()));
        assertTrue(legacyConfig.toFile().isFile(), "legacy file must be kept as a backup");
        assertTrue(legacyClientId.toFile().isFile(), "legacy file must be kept as a backup");
    }

    @Test
    void existingCurrentFileIsNeverOverwritten() throws IOException {
        Path configDirectory = Files.createDirectories(temporaryDirectory.resolve("config"));
        write(configDirectory.resolve("enterprise_config_dev.json"), "legacy".getBytes());
        Path currentConfig = write(configDirectory.resolve("runtime_config_dev.json"), "current".getBytes());

        ConfigUtils.migrateLegacyFile(
                configDirectory.resolve("enterprise_config_dev.json").toFile(),
                currentConfig.toFile(), "runtime config");

        assertArrayEquals("current".getBytes(), Files.readAllBytes(currentConfig));
    }

    @Test
    void missingLegacyFileCreatesNothingAndDoesNotThrow() {
        File legacy = temporaryDirectory.resolve("enterprise_client_uuid").toFile();
        File current = temporaryDirectory.resolve("client_uuid").toFile();

        ConfigUtils.migrateLegacyFile(legacy, current, "client id");

        assertFalse(current.exists());
        assertFalse(legacy.exists());
    }

    @Test
    void migrationCreatesMissingParentDirectories() throws IOException {
        Path legacy = write(temporaryDirectory.resolve("enterprise_client_uuid"), "abc".getBytes());
        File current = temporaryDirectory.resolve("nested").resolve("client_uuid").toFile();

        ConfigUtils.migrateLegacyFile(legacy.toFile(), current, "client id");

        assertArrayEquals("abc".getBytes(), Files.readAllBytes(current.toPath()));
    }

    @Test
    void failedCopyNeverPublishesOrLeavesAPartialCurrentFile() throws IOException {
        Path configDirectory = Files.createDirectories(temporaryDirectory.resolve("config"));
        Path legacy = write(configDirectory.resolve("enterprise_config_dev.json"), "complete".getBytes());
        Path current = configDirectory.resolve("runtime_config_dev.json");

        ConfigUtils.migrateLegacyFile(legacy.toFile(), current.toFile(), "runtime config", (source, staging) -> {
            Files.writeString(staging, "partial");
            throw new IOException("injected copy failure");
        });

        assertFalse(Files.exists(current));
        assertTrue(Files.exists(legacy));
        try (var files = Files.list(configDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".migrating-")));
        }
    }

    private static Path write(Path path, byte[] content) throws IOException {
        Files.write(path, content);
        return path;
    }
}
