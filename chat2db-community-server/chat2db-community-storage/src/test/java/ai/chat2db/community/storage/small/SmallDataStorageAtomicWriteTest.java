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
}
