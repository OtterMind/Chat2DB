package ai.chat2db.community.storage.small;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ai.chat2db.community.domain.api.enums.NodeTypeEnum;
import ai.chat2db.community.domain.api.model.workspace.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeNodeStorageTest {

    @TempDir
    File tempDir;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void dropToNullRootDoesNotDuplicateDraggedNode() {
        TreeNodeStorage storage = newStorage("move-to-root.json");
        Node nodeA = dataSourceNode(1L);
        Node nodeB = dataSourceNode(2L);
        storage.createTree(new ArrayList<>(List.of(nodeA, nodeB)));

        storage.updatePosition(null, dataSourceNode(2L), null);

        List<Node> nodes = storage.getNodes();
        assertEquals(2, nodes.size());
        long occurrencesOfDragged = nodes.stream()
                .filter(node -> Long.valueOf(2L).equals(node.getId())
                        && NodeTypeEnum.DATA_SOURCE.name().equals(node.getType()))
                .count();
        assertEquals(1, occurrencesOfDragged);
    }

    @Test
    void interruptStatusIsRestoredAfterUpdatePosition() {
        TreeNodeStorage storage = newStorage("interrupt.json");
        Thread.currentThread().interrupt();
        storage.updatePosition(null, dataSourceNode(99L), null);
        assertTrue(Thread.currentThread().isInterrupted(),
                "interrupt flag must be restored after InterruptedException");
    }

    @Test
    void deleteLastNodePersistsAnEmptyTree() {
        TreeNodeStorage storage = newStorage("delete-last.json");
        Node onlyNode = Node.builder()
                .id(1L)
                .type(NodeTypeEnum.DATA_SOURCE.name())
                .build();
        storage.createTree(new ArrayList<>(List.of(onlyNode)));

        storage.deleteNode(onlyNode);

        assertTrue(storage.getNodes().isEmpty());
        assertTrue(newStorage("delete-last.json").getNodes().isEmpty());
    }

    @Test
    void missingDropTargetPreservesSourceInMemoryAndOnReload() {
        assertInvalidDropPreservesTree(
                "missing-target.json",
                dataSourceNode(999L),
                dataSourceNode(3L));
    }

    @Test
    void selfDropTargetPreservesSourceInMemoryAndOnReload() {
        assertInvalidDropPreservesTree(
                "self-target.json",
                dataSourceNode(3L),
                dataSourceNode(3L));
    }

    @Test
    void descendantDropTargetPreservesSourceInMemoryAndOnReload() {
        assertInvalidDropPreservesTree(
                "descendant-target.json",
                dataSourceNode(2L),
                namespaceNode(1L, dataSourceNode(2L)));
    }

    @Test
    void validDropStillMovesSourceInMemoryAndOnReload() {
        String fileName = "valid-target.json";
        TreeNodeStorage storage = newStorage(fileName);
        storage.createTree(new ArrayList<>(List.of(
                namespaceNode(1L, dataSourceNode(2L)),
                dataSourceNode(3L))));
        List<Node> expected = List.of(namespaceNode(1L, dataSourceNode(2L), dataSourceNode(3L)));

        storage.updatePosition(namespaceNode(1L), dataSourceNode(3L), 2);

        assertEquals(expected, storage.getNodes());
        assertEquals(expected, newStorage(fileName).getNodes());
    }

    @Test
    void failedMovePersistenceKeepsInMemoryAndPersistedTree() throws Exception {
        String fileName = "failed-move.json";
        File storageFile = new File(tempDir, fileName);
        List<Node> expected = List.of(
                namespaceNode(1L, dataSourceNode(2L)),
                dataSourceNode(3L));
        TreeNodeStorage initial = new TreeNodeStorage(storageFile);
        initial.createTree(new ArrayList<>(expected));
        String originalFile = Files.readString(storageFile.toPath());
        TreeNodeStorage failing = new FailingTreeNodeStorage(storageFile);

        assertThrows(RuntimeException.class,
                () -> failing.updatePosition(namespaceNode(1L), dataSourceNode(3L), 2));

        assertEquals(expected, failing.getNodes(), "failed replace must not publish the candidate tree in memory");
        assertEquals(originalFile, Files.readString(storageFile.toPath()),
                "failed replace must leave the persisted file untouched");
        assertEquals(expected, new TreeNodeStorage(storageFile).getNodes());
    }

    @Test
    void failedDeletePersistenceKeepsInMemoryAndPersistedTree() throws Exception {
        String fileName = "failed-delete.json";
        File storageFile = new File(tempDir, fileName);
        List<Node> expected = List.of(
                namespaceNode(1L, dataSourceNode(2L)),
                dataSourceNode(3L));
        TreeNodeStorage initial = new TreeNodeStorage(storageFile);
        initial.createTree(new ArrayList<>(expected));
        String originalFile = Files.readString(storageFile.toPath());
        TreeNodeStorage failing = new FailingTreeNodeStorage(storageFile);

        assertThrows(RuntimeException.class, () -> failing.deleteNode(namespaceNode(1L)));

        assertEquals(expected, failing.getNodes(), "failed replace must not publish the candidate deletion in memory");
        assertEquals(originalFile, Files.readString(storageFile.toPath()),
                "failed replace must leave the persisted file untouched");
        assertEquals(expected, new TreeNodeStorage(storageFile).getNodes());
    }

    private void assertInvalidDropPreservesTree(String fileName, Node dropToNode, Node dragNode) {
        TreeNodeStorage storage = newStorage(fileName);
        List<Node> expected = List.of(
                namespaceNode(1L, dataSourceNode(2L)),
                dataSourceNode(3L));
        storage.createTree(new ArrayList<>(expected));

        storage.updatePosition(dropToNode, dragNode, 0);

        assertEquals(expected, storage.getNodes(), "invalid drop must preserve the in-memory tree");
        assertEquals(expected, newStorage(fileName).getNodes(), "invalid drop must preserve the persisted tree");
    }

    private TreeNodeStorage newStorage(String fileName) {
        return new TreeNodeStorage(new File(tempDir, fileName));
    }

    private static Node dataSourceNode(Long id) {
        return Node.builder().id(id).type(NodeTypeEnum.DATA_SOURCE.name()).build();
    }

    private static Node namespaceNode(Long id, Node... children) {
        return Node.builder()
                .id(id)
                .type(NodeTypeEnum.NAMESPACE.name())
                .children(new ArrayList<>(List.of(children)))
                .build();
    }

    private static final class FailingTreeNodeStorage extends TreeNodeStorage {

        private FailingTreeNodeStorage(File storageFile) {
            super(storageFile);
        }

        @Override
        protected void replaceStorageFile(Path temp, Path target) throws IOException {
            throw new IOException("forced replace failure");
        }
    }
}
