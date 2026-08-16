package ai.chat2db.community.storage.small;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ai.chat2db.community.domain.api.enums.NodeTypeEnum;
import ai.chat2db.community.domain.api.model.workspace.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private TreeNodeStorage newStorage(String fileName) {
        return new TreeNodeStorage(new File(tempDir, fileName));
    }

    private static Node dataSourceNode(Long id) {
        return Node.builder().id(id).type(NodeTypeEnum.DATA_SOURCE.name()).build();
    }
}
