package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.model.workspace.Namespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NamespaceStorageAtomicWriteTest {

    @TempDir
    Path tempDir;

    @Test
    void failedPositionUpdateKeepsMemoryFileAndReloadedState() throws Exception {
        File storageFile = tempDir.resolve("update-namespace.json").toFile();
        FailingNamespaceStorage storage = new FailingNamespaceStorage(storageFile);
        storage.save(namespace(1L, 11L));
        storage.save(namespace(2L, 22L));
        byte[] originalBytes = Files.readAllBytes(storageFile.toPath());
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.updateDataSourcePosition(2L, 11L));

        assertEquals(List.of(11L), storage.getById(1L).getDatasourceIds());
        assertEquals(List.of(22L), storage.getById(2L).getDatasourceIds());
        assertArrayEquals(originalBytes, Files.readAllBytes(storageFile.toPath()));
        NamespaceStorage reloaded = new NamespaceStorage(storageFile);
        assertEquals(List.of(11L), reloaded.getById(1L).getDatasourceIds());
        assertEquals(List.of(22L), reloaded.getById(2L).getDatasourceIds());
    }

    @Test
    void failedPositionDeleteKeepsMemoryFileAndReloadedState() throws Exception {
        File storageFile = tempDir.resolve("delete-namespace.json").toFile();
        FailingNamespaceStorage storage = new FailingNamespaceStorage(storageFile);
        storage.save(namespace(1L, 11L, 12L));
        byte[] originalBytes = Files.readAllBytes(storageFile.toPath());
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.deleteDataSourcePosition(11L));

        assertEquals(List.of(11L, 12L), storage.getById(1L).getDatasourceIds());
        assertArrayEquals(originalBytes, Files.readAllBytes(storageFile.toPath()));
        NamespaceStorage reloaded = new NamespaceStorage(storageFile);
        assertEquals(List.of(11L, 12L), reloaded.getById(1L).getDatasourceIds());
    }

    @Test
    void successfulPositionChangesPublishAndReload() {
        File storageFile = tempDir.resolve("successful-namespace.json").toFile();
        NamespaceStorage storage = new NamespaceStorage(storageFile);
        storage.save(namespace(1L, 11L));
        storage.save(namespace(2L, 22L));

        storage.updateDataSourcePosition(2L, 11L);

        assertEquals(List.of(), storage.getById(1L).getDatasourceIds());
        assertEquals(List.of(22L, 11L), storage.getById(2L).getDatasourceIds());
        NamespaceStorage movedReload = new NamespaceStorage(storageFile);
        assertEquals(List.of(), movedReload.getById(1L).getDatasourceIds());
        assertEquals(List.of(22L, 11L), movedReload.getById(2L).getDatasourceIds());

        storage.deleteDataSourcePosition(11L);

        assertEquals(List.of(22L), storage.getById(2L).getDatasourceIds());
        NamespaceStorage deletedReload = new NamespaceStorage(storageFile);
        assertEquals(List.of(22L), deletedReload.getById(2L).getDatasourceIds());
    }

    @Test
    void nullNamespaceMovesDataSourceToRootAndReloads() throws Exception {
        File storageFile = tempDir.resolve("root-namespace.json").toFile();
        NamespaceStorage storage = new NamespaceStorage(storageFile);
        storage.save(namespace(1L, 11L, 12L));

        storage.updateDataSourcePosition(null, 11L);

        assertEquals(List.of(12L), storage.getById(1L).getDatasourceIds());
        NamespaceStorage reloaded = new NamespaceStorage(storageFile);
        assertEquals(List.of(12L), reloaded.getById(1L).getDatasourceIds());
    }

    private Namespace namespace(Long id, Long... datasourceIds) {
        Namespace namespace = new Namespace();
        namespace.setId(id);
        namespace.setName("namespace-" + id);
        namespace.setDatasourceIds(new ArrayList<>(List.of(datasourceIds)));
        return namespace;
    }

    private static class FailingNamespaceStorage extends NamespaceStorage {

        private boolean failWrites;

        private FailingNamespaceStorage(File storageFile) {
            super(storageFile);
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
