package ai.chat2db.community.storage.small;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.chat2db.community.domain.api.model.db.TreeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallDataStorageAtomicWriteTest {

    @TempDir
    File tempDir;

    @Test
    void saveDataListCreatesMissingDirectory() throws Exception {
        File storageFile = new File(new File(tempDir, "missing"), "tree.json");
        TestStorage storage = new TestStorage(storageFile);
        Files.delete(storageFile.toPath());
        Files.delete(storageFile.getParentFile().toPath());
        storage.put(treeNode(1L));

        storage.persist();

        assertTrue(storageFile.isFile());
        assertNotNull(new TestStorage(storageFile).getById(1L));
    }

    @Test
    void saveDataListDoesNotReuseLegacyFixedTempFile() throws Exception {
        File storageFile = new File(tempDir, "tree.json");
        TestStorage storage = new TestStorage(storageFile);
        Path unrelatedTemp = Path.of(storageFile + ".tmp");
        Files.writeString(unrelatedTemp, "keep", StandardCharsets.UTF_8);
        storage.put(treeNode(1L));

        storage.persist();

        assertEquals("keep", Files.readString(unrelatedTemp, StandardCharsets.UTF_8));
    }

    @Test
    void failedReplaceKeepsOriginalFileAndCleansTemporaryFile() throws Exception {
        File storageFile = new File(tempDir, "tree.json");
        TestStorage initial = new TestStorage(storageFile);
        initial.put(treeNode(1L));
        initial.persist();
        String original = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
        FailingStorage failing = new FailingStorage(storageFile);
        failing.put(treeNode(2L));

        assertThrows(RuntimeException.class, failing::persist);

        assertEquals(original, Files.readString(storageFile.toPath(), StandardCharsets.UTF_8));
        try (var files = Files.list(tempDir.toPath())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void failedExistingSaveKeepsMemoryFileAndReloadState() throws Exception {
        File storageFile = new File(tempDir, "records-save.json");
        FailingRecordStorage storage = seededStorage(storageFile);
        String original = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.save(record(1L, "after")));

        assertNotNull(storage.getById(1L));
        assertEquals("before", storage.getById(1L).getValue());
        assertEquals(original, Files.readString(storageFile.toPath(), StandardCharsets.UTF_8));
        assertEquals("before", new RecordStorage(storageFile).getById(1L).getValue());
    }

    @Test
    void failedUpdateKeepsMemoryFileAndReloadState() throws Exception {
        File storageFile = new File(tempDir, "records-update.json");
        FailingRecordStorage storage = seededStorage(storageFile);
        String original = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.update(record(1L, "after")));

        assertNotNull(storage.getById(1L));
        assertEquals("before", storage.getById(1L).getValue());
        assertEquals(original, Files.readString(storageFile.toPath(), StandardCharsets.UTF_8));
        assertEquals("before", new RecordStorage(storageFile).getById(1L).getValue());
    }

    @Test
    void failedDeleteKeepsMemoryFileAndReloadState() throws Exception {
        File storageFile = new File(tempDir, "records-delete.json");
        FailingRecordStorage storage = seededStorage(storageFile);
        String original = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
        storage.failWrites = true;

        assertThrows(RuntimeException.class, () -> storage.delete(1L));

        assertNotNull(storage.getById(1L));
        assertEquals("before", storage.getById(1L).getValue());
        assertEquals(original, Files.readString(storageFile.toPath(), StandardCharsets.UTF_8));
        assertEquals("before", new RecordStorage(storageFile).getById(1L).getValue());
    }

    @Test
    void failedNewSaveKeepsMemoryAndReloadStateEmpty() throws Exception {
        File storageFile = new File(tempDir, "records-new.json");
        RecordStorage storage = new RecordStorage(storageFile);
        String original = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
        Files.delete(storageFile.toPath());
        Files.createDirectory(storageFile.toPath());
        Path blocker = Files.writeString(storageFile.toPath().resolve("blocker"), "block");

        assertThrows(RuntimeException.class, () -> storage.save(record(1L, "new")));

        Files.delete(blocker);
        Files.delete(storageFile.toPath());
        Files.writeString(storageFile.toPath(), original, StandardCharsets.UTF_8);
        assertNull(storage.getById(1L));
        assertNull(new RecordStorage(storageFile).getById(1L));
    }

    private static FailingRecordStorage seededStorage(File storageFile) {
        FailingRecordStorage storage = new FailingRecordStorage(storageFile);
        storage.save(record(1L, "before"));
        return storage;
    }

    private static TestRecord record(long id, String value) {
        TestRecord record = new TestRecord();
        record.setId(id);
        record.setValue(value);
        return record;
    }

    private static TreeNode treeNode(long id) {
        TreeNode node = new TreeNode();
        node.setId(id);
        return node;
    }

    private static class TestStorage extends SmallDataStorage<TreeNode> {

        TestStorage(File storageFile) {
            super(storageFile, TreeNode.class);
        }

        void put(TreeNode node) {
            dataMap.put(node.getId(), node);
        }

        void persist() {
            saveDataList();
        }
    }

    private static class FailingStorage extends TestStorage {

        FailingStorage(File storageFile) {
            super(storageFile);
        }

        @Override
        protected void replaceStorageFile(Path temp, Path target) throws IOException {
            throw new IOException("forced replace failure");
        }
    }

    public static class TestRecord {
        private Long id;
        private String value;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    private static class RecordStorage extends SmallDataStorage<TestRecord> {
        RecordStorage(File storageFile) {
            super(storageFile, TestRecord.class);
        }
    }

    private static final class FailingRecordStorage extends RecordStorage {
        private boolean failWrites;

        FailingRecordStorage(File storageFile) {
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
