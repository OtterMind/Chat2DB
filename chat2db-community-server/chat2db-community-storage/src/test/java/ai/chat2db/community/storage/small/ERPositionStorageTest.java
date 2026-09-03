package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.model.er.ERPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression test for {@link ERPositionStorage#savePosition}.
 * Verifies that saving a new position for an existing logical key
 * replaces the old position without duplicating the record.
 */
class ERPositionStorageTest {

    @TempDir
    File tempDir;

    private File storageFile() {
        return new File(tempDir, "er_position.json");
    }

    private ERPositionStorage createStorage() {
        return new ERPositionStorage(storageFile());
    }

    private ERPosition pos(Long dataSourceId, String db, String schema, String position) {
        ERPosition p = new ERPosition();
        p.setDataSourceId(dataSourceId);
        p.setDatabaseName(db);
        p.setSchemaName(schema);
        p.setPosition(position);
        return p;
    }

    @Test
    void savePositionInsertsNewRecord() throws Exception {
        ERPositionStorage storage = createStorage();
        storage.savePosition(pos(1L, "db1", "schema1", "pos1"));

        assertEquals("pos1", storage.getPosition(1L, "db1", "schema1"));
        assertEquals(1, storage.getDataList().size());
    }

    @Test
    void savePositionReplacesExistingPositionAndPersistsIt() {
        ERPositionStorage storage = createStorage();
        storage.savePosition(pos(1L, "db1", "schema1", "pos1"));
        storage.savePosition(pos(1L, "db1", "schema1", "pos2"));

        ERPositionStorage reloaded = createStorage();
        assertEquals("pos2", reloaded.getPosition(1L, "db1", "schema1"));
        assertEquals(1, reloaded.getDataList().size(), "Should not duplicate records");
    }

    @Test
    void savePositionDoesNotAffectOtherKeys() throws Exception {
        ERPositionStorage storage = createStorage();
        storage.savePosition(pos(1L, "db1", "schema1", "pos1"));
        storage.savePosition(pos(2L, "db2", "schema2", "pos2"));
        storage.savePosition(pos(1L, "db1", "schema1", "pos1_updated"));

        assertEquals("pos1_updated", storage.getPosition(1L, "db1", "schema1"));
        assertEquals("pos2", storage.getPosition(2L, "db2", "schema2"));
        assertEquals(2, storage.getDataList().size());
    }

    @Test
    void getPositionReturnsNullForMissingKey() throws Exception {
        ERPositionStorage storage = createStorage();
        assertNull(storage.getPosition(99L, "missing", "missing"));
    }

    @Test
    void failedPositionReplacementKeepsMemoryFileAndReloadState() throws Exception {
        File storageFile = storageFile();
        FailingERPositionStorage storage = new FailingERPositionStorage(storageFile);
        storage.savePosition(pos(1L, "db1", "schema1", "old"));
        ERPosition before = storage.getDataList().get(0);
        String persisted = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
        storage.failWrites = true;

        assertThrows(RuntimeException.class,
                () -> storage.savePosition(pos(1L, "db1", "schema1", "new")));

        ERPositionStorage reloaded = new ERPositionStorage(storageFile);
        assertAll(
                () -> assertSame(before, storage.getDataList().get(0)),
                () -> assertEquals("old", storage.getPosition(1L, "db1", "schema1")),
                () -> assertEquals(persisted,
                        Files.readString(storageFile.toPath(), StandardCharsets.UTF_8)),
                () -> assertEquals("old", reloaded.getPosition(1L, "db1", "schema1"))
        );
    }

    private static final class FailingERPositionStorage extends ERPositionStorage {

        private boolean failWrites;

        private FailingERPositionStorage(File storageFile) {
            super(storageFile);
        }

        @Override
        protected void replaceStorageFile(Path temp, Path target) throws IOException {
            if (failWrites) {
                throw new IOException("forced replace failure");
            }
            super.replaceStorageFile(temp, target);
        }
    }
}
