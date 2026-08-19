package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.model.datasource.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataSourceIdentityColorStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void identityColorSurvivesJsonStorageReload() {
        File storageFile = tempDir.resolve("datasource.json").toFile();
        TestDataSourceStorage storage = new TestDataSourceStorage(storageFile);
        DataSource dataSource = new DataSource();
        dataSource.setAlias("colored datasource");
        dataSource.setIdentityColor("#A1B2C3");

        Long id = storage.save(dataSource);
        TestDataSourceStorage reloaded = new TestDataSourceStorage(storageFile);

        assertEquals("#A1B2C3", reloaded.getById(id).getIdentityColor());
    }

    @Test
    void legacyJsonWithoutIdentityColorLoadsWithNullValue() throws Exception {
        File storageFile = tempDir.resolve("legacy-datasource.json").toFile();
        Files.writeString(storageFile.toPath(), "{\"id\":71,\"alias\":\"legacy\"}\n",
                StandardCharsets.UTF_8);

        TestDataSourceStorage storage = new TestDataSourceStorage(storageFile);

        assertEquals("legacy", storage.getById(71L).getAlias());
        assertNull(storage.getById(71L).getIdentityColor());
        assertNull(storage.getById(71L).getWatermarkEnabled());
        assertNull(storage.getById(71L).getWatermarkContent());
    }

    @Test
    void watermarkSettingsSurviveJsonStorageReloadAndFullUpdate() {
        File storageFile = tempDir.resolve("watermark-datasource.json").toFile();
        AtomicDataSourceStorage storage = new AtomicDataSourceStorage(storageFile);
        DataSource dataSource = new DataSource();
        dataSource.setAlias("watermarked datasource");
        dataSource.setWatermarkEnabled(true);
        dataSource.setWatermarkContent("Finance Read Only");
        Long id = storage.save(dataSource);

        DataSource update = new DataSource();
        update.setId(id);
        update.setWatermarkEnabled(false);
        update.setWatermarkContent("");
        storage.update(update);

        assertFalse(storage.getById(id).getWatermarkEnabled());
        assertEquals("", storage.getById(id).getWatermarkContent());
        AtomicDataSourceStorage reloaded = new AtomicDataSourceStorage(storageFile);
        assertFalse(reloaded.getById(id).getWatermarkEnabled());
        assertEquals("", reloaded.getById(id).getWatermarkContent());
    }

    @Test
    void fullUpdatePreservesTheLatestIdentityColorWhenTheFieldIsOmitted() {
        File storageFile = tempDir.resolve("atomic-update-datasource.json").toFile();
        AtomicDataSourceStorage storage = new AtomicDataSourceStorage(storageFile);
        DataSource dataSource = new DataSource();
        dataSource.setAlias("before");
        dataSource.setIdentityColor("#112233");
        Long id = storage.save(dataSource);

        storage.updateIdentityColor(id, "  #aabbcc  ");
        DataSource connectionUpdate = new DataSource();
        connectionUpdate.setId(id);
        connectionUpdate.setAlias("after");
        storage.update(connectionUpdate);

        assertEquals("after", storage.getById(id).getAlias());
        assertEquals("#AABBCC", storage.getById(id).getIdentityColor());
    }

    @Test
    void fullUpdateCannotOverwriteLatestIdentityColorWithAStaleNonNullValue() {
        File storageFile = tempDir.resolve("stale-color-update-datasource.json").toFile();
        AtomicDataSourceStorage storage = new AtomicDataSourceStorage(storageFile);
        DataSource dataSource = new DataSource();
        dataSource.setAlias("before");
        dataSource.setIdentityColor("#112233");
        Long id = storage.save(dataSource);
        DataSource staleConnectionUpdate = new DataSource();
        staleConnectionUpdate.setId(id);
        staleConnectionUpdate.setAlias("after");
        staleConnectionUpdate.setIdentityColor("#112233");

        storage.updateIdentityColor(id, "#AABBCC");
        storage.update(staleConnectionUpdate);

        assertEquals("after", storage.getById(id).getAlias());
        assertEquals("#AABBCC", storage.getById(id).getIdentityColor());
        AtomicDataSourceStorage reloaded = new AtomicDataSourceStorage(storageFile);
        assertEquals("#AABBCC", reloaded.getById(id).getIdentityColor());
    }

    @Test
    void failedIdentityColorWriteDoesNotChangeMemoryOrDisk() {
        File storageFile = tempDir.resolve("failed-color-datasource.json").toFile();
        AtomicDataSourceStorage storage = new AtomicDataSourceStorage(storageFile);
        DataSource dataSource = new DataSource();
        dataSource.setAlias("stable");
        dataSource.setIdentityColor("#112233");
        Long id = storage.save(dataSource);
        DataSource before = storage.getById(id);
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.updateIdentityColor(id, "#445566"));

        assertSame(before, storage.getById(id));
        assertEquals("#112233", storage.getById(id).getIdentityColor());
        AtomicDataSourceStorage reloaded = new AtomicDataSourceStorage(storageFile);
        assertEquals("#112233", reloaded.getById(id).getIdentityColor());
    }

    @Test
    void failedFullUpdateDoesNotChangeMemoryOrDisk() {
        File storageFile = tempDir.resolve("failed-full-update-datasource.json").toFile();
        AtomicDataSourceStorage storage = new AtomicDataSourceStorage(storageFile);
        DataSource dataSource = new DataSource();
        dataSource.setAlias("stable");
        dataSource.setIdentityColor("#112233");
        Long id = storage.save(dataSource);
        DataSource before = storage.getById(id);
        storage.failWrites = true;
        DataSource update = new DataSource();
        update.setId(id);
        update.setAlias("not persisted");

        assertThrows(RuntimeException.class, () -> storage.update(update));

        assertSame(before, storage.getById(id));
        assertEquals("stable", storage.getById(id).getAlias());
        AtomicDataSourceStorage reloaded = new AtomicDataSourceStorage(storageFile);
        assertEquals("stable", reloaded.getById(id).getAlias());
    }

    private static class TestDataSourceStorage extends SmallDataStorage<DataSource> {

        private TestDataSourceStorage(File storageFile) {
            super(storageFile, DataSource.class);
        }
    }

    private static class AtomicDataSourceStorage extends DataSourceStorage {

        private boolean failWrites;

        private AtomicDataSourceStorage(File storageFile) {
            super(storageFile);
        }

        @Override
        protected synchronized void createDataSourceNode(Long datasourceId, Long namespaceId) {
        }

        @Override
        protected void replaceStorageFile(Path temp, Path target) throws IOException {
            if (failWrites) {
                throw new IOException("simulated persistence failure");
            }
            super.replaceStorageFile(temp, target);
        }
    }
}
