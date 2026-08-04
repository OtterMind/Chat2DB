package ai.chat2db.community.storage.small;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ai.chat2db.community.domain.api.enums.NodeTypeEnum;
import ai.chat2db.community.domain.api.model.workspace.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeNodeStorageTest {

    @TempDir
    File tempDir;

    @Test
    void deleteLastNodePersistsAnEmptyTree() {
        File storageFile = new File(tempDir, "tree.json");
        TreeNodeStorage storage = new TreeNodeStorage(storageFile);
        Node onlyNode = Node.builder()
                .id(1L)
                .type(NodeTypeEnum.DATA_SOURCE.name())
                .build();
        storage.createTree(new ArrayList<>(List.of(onlyNode)));

        storage.deleteNode(onlyNode);

        assertTrue(storage.getNodes().isEmpty());
        assertTrue(new TreeNodeStorage(storageFile).getNodes().isEmpty());
    }
}
