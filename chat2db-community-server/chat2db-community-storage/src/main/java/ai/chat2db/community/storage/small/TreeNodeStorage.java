package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.enums.NodeTypeEnum;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.model.db.TreeNode;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.filter.PropertyFilter;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
public class TreeNodeStorage extends SmallDataStorage<TreeNode> {
    public static final TreeNodeStorage INSTANCE = new TreeNodeStorage();

    protected TreeNodeStorage() {
        super("tree", TreeNode.class);
        if (MapUtils.isEmpty(dataMap)) {

        }
    }

    TreeNodeStorage(File storageFile) {
        super(storageFile, TreeNode.class);
    }

    public synchronized List<Node> getNodes() {
        List<TreeNode> treeNodes = getDataList();
        if (treeNodes == null) {
            return null;
        }
        if (CollectionUtils.isEmpty(treeNodes)) {
            return Lists.newArrayList();
        }
        return treeNodes.get(0).getChildren();
    }

    public synchronized void createTree(List<Node> nodes) {
        if (nodes == null) {
            return;
        }
        PropertyFilter filter = (object, name, value) -> !"data".equals(name);
        String json = JSON.toJSONString(nodes, filter);
        List<Node> newNodes = JSON.parseArray(json, Node.class);

        List<TreeNode> treeNodes = getDataList();
        if (CollectionUtils.isEmpty(treeNodes)) {
            TreeNode treeNode = new TreeNode();
            treeNode.setChildren(newNodes);
            save(treeNode);
        } else {
            treeNodes.get(0).setChildren(newNodes);
            update(treeNodes.get(0));
        }
    }


    public synchronized ActionResult updatePosition(Node dropToNode, Node dragNode, Integer dropPosition) {
        if (dragNode == null) {
            return ActionResult.isSuccess();
        }
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Node> nodes = getNodes();
        if (nodes == null) {
            return ActionResult.isSuccess();
        }
        if (dropToNode == null) {
            removeNode(nodes, dragNode, false);
            nodes.add(dragNode);
            createTree(nodes);
            return ActionResult.isSuccess();
        } else {
            removeNode(nodes, dragNode, false);
            addNode(nodes, dropToNode, dragNode, dropPosition);
            createTree(nodes);
        }
        return ActionResult.isSuccess();
    }

    private synchronized void removeNode(List<Node> nodes, Node dragNode, boolean deleteChildren) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }
        Iterator<Node> iterator = nodes.iterator();
        List<Node> tempList = new ArrayList<>();
        while (iterator.hasNext()) {
            Node node = iterator.next();
            if (node.getId().equals(dragNode.getId()) && node.getType().equals(dragNode.getType())) {
                if (NodeTypeEnum.NAMESPACE.name().equals(node.getType())) {
                    List<Node> c = node.getChildren();
                    if (CollectionUtils.isNotEmpty(c)) {
                        tempList.addAll(c);
                        dragNode.setChildren(c);
                    }
                }
                iterator.remove();
                if (CollectionUtils.isNotEmpty(tempList) && deleteChildren) {
                    nodes.addAll(tempList);
                }
                return;
            }
            removeNode(node.getChildren(), dragNode, deleteChildren);
        }
    }

    public synchronized ActionResult deleteNode(Node dragNode) {
        List<Node> nodes = getNodes();
        removeNode(nodes, dragNode, true);
        createTree(nodes);
        return ActionResult.isSuccess();
    }

    private synchronized void addNode(List<Node> nodes, Node dropToNode, Node dragNode, Integer dropToGap) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }
        if (dropToNode.getId().equals(dragNode.getId()) && dropToNode.getType().equals(dragNode.getType())) {
            return;
        }
        int index = 0;
        for (Node node : nodes) {
            index++;
            if (node.getId().equals(dropToNode.getId()) && node.getType().equals(dropToNode.getType())) {
                if (dropToGap == 0) {
                    List<Node> children = node.getChildren();
                    if (children == null) {
                        children = new ArrayList<>();
                    }
                    children.add(0, dragNode);
                    node.setChildren(children);
                    return;
                }else if (dropToGap == 2) {
                    List<Node> children = node.getChildren();
                    if (children == null) {
                        children = new ArrayList<>();
                    }
                    children.add(dragNode);
                    node.setChildren(children);
                    return;
                }else if (dropToGap == 1) {
                    nodes.add(index, dragNode);
                    return;
                } else {
                    nodes.add(index - 1, dragNode);
                    return;
                }
            }
            addNode(node.getChildren(), dropToNode, dragNode, dropToGap);
        }
    }
}
